package de.jplag.java_cpg;

import static de.jplag.java_cpg.transformation.TransformationRepository.inlineSingleUseVariable;
import static de.jplag.java_cpg.transformation.TransformationRepository.removeOptionalGetCall;
import static de.jplag.java_cpg.transformation.matching.edges.Edges.BLOCK__STATEMENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import de.fraunhofer.aisec.cpg.TranslationContext;
import de.fraunhofer.aisec.cpg.TranslationResult;
import de.fraunhofer.aisec.cpg.graph.declarations.VariableDeclaration;
import de.fraunhofer.aisec.cpg.graph.edge.Properties;
import de.fraunhofer.aisec.cpg.graph.statements.IfStatement;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.BinaryOperator;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Reference;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker;
import de.jplag.ParsingException;
import de.jplag.java_cpg.transformation.GraphTransformation;
import de.jplag.java_cpg.transformation.TransformationRepository;
import de.jplag.java_cpg.transformation.matching.CpgIsomorphismDetector;
import de.jplag.java_cpg.transformation.matching.edges.CpgNthEdge;
import de.jplag.java_cpg.transformation.matching.pattern.GraphPattern;
import de.jplag.java_cpg.transformation.matching.pattern.Match;

import de.jplag.java_cpg.transformation.operations.GraphMutationContext;
import de.jplag.java_cpg.transformation.operations.RemoveOperation;
import de.jplag.java_cpg.transformation.operations.TransformationUtil;

/**
 * Tests the creation of transformations.
 */
public class CreateTransformTest extends AbstractJavaCpgLanguageTest {

    private CpgIsomorphismDetector detector;

    @TempDir
    private Path directory;

    static Stream<Arguments> provideTuples() {
        return Stream.of(
                Arguments.of("UnusedVariableDeclaration.java", TransformationRepository.removeUnusedVariableDeclaration, VariableDeclaration.class),
                Arguments.of("IfElseWithNegatedCondition.java", TransformationRepository.ifWithNegatedConditionResolution, IfStatement.class));
    }

    @ParameterizedTest
    @MethodSource("provideTuples")
    void createTransformTest(String fileName, GraphTransformation transformation) throws ParsingException, InterruptedException {

        Set<File> files = Set.of(new File(baseDirectory, fileName));
        CpgAdapter cpgAdapter = new CpgAdapter();
        cpgAdapter.clearTransformations();
        TranslationResult graph = cpgAdapter.translate(files);

        detector = new CpgIsomorphismDetector();
        detector.loadGraph(graph);

        instantiate(transformation);

    }

    @ParameterizedTest
    @ValueSource(strings = {"&&", "||"})
    void inliningPreservesShortCircuitBranches(String operator) throws Exception {
        var expression = assertBranches(
                "class Example { static boolean test(int input) { boolean positive = input > 0; return positive " + operator + " input < 10; } }",
                operator, inlineSingleUseVariable);
        assertInstanceOf(BinaryOperator.class, expression.getLhs());
    }

    @ParameterizedTest
    @ValueSource(strings = {"&&", "||"})
    void inliningTheRightOperandPreservesShortCircuitBranches(String operator) throws Exception {
        var expression = assertBranches(
                "class Example { static boolean test(int input) { boolean positive = input > 0; return input < 10 " + operator + " positive; } }",
                operator, inlineSingleUseVariable);
        assertInstanceOf(BinaryOperator.class, expression.getRhs());
    }

    @ParameterizedTest
    @ValueSource(strings = {"&&", "||"})
    void optionalRemovalPreservesShortCircuitBranches(String operator) throws Exception {
        var expression = assertBranches(
                "import java.util.Optional; class Example { static boolean test(Optional<Boolean> first, boolean second) { return first.get() "
                        + operator + " second; } }",
                operator, removeOptionalGetCall);
        assertInstanceOf(Reference.class, expression.getLhs());
    }

    @ParameterizedTest
    @ValueSource(strings = {"while(q) {}", "do {} while(q);"})
    void removingAnInnerLoopKeepsTheOuterBranch(String loop) throws Exception {
        var graph = translate("class Example { static void test(boolean p, boolean q) { if(p) { " + loop + " } int x = 1; } }");
        var outer = SubgraphWalker.INSTANCE.flattenAST(graph).stream().filter(IfStatement.class::isInstance).map(IfStatement.class::cast).findFirst()
                .orElseThrow();
        var parent = (Block) outer.getThenStatement();
        var removed = parent.getStatements().getFirst();
        var unchanged = outer.getNextEOGEdges().stream().filter(edge -> !Boolean.TRUE.equals(edge.getProperty(Properties.BRANCH))).toList();

        try (var context = new GraphMutationContext()) {
            RemoveOperation.apply(parent, removed, new CpgNthEdge<>(BLOCK__STATEMENTS, 0), true, context);
        }

        assertTrue(parent.getStatements().isEmpty());
        assertEquals(unchanged.size() + 1, outer.getNextEOGEdges().size());
        unchanged.forEach(edge -> assertTrue(outer.getNextEOGEdges().stream().anyMatch(candidate -> candidate == edge)));
        var taken = outer.getNextEOGEdges().stream().filter(edge -> Boolean.TRUE.equals(edge.getProperty(Properties.BRANCH))).findFirst()
                .orElseThrow();
        assertSame(parent, taken.getEnd());
        assertTrue(parent.getPrevEOGEdges().stream().anyMatch(edge -> edge == taken));
    }

    private TranslationResult translate(String source, GraphTransformation... transformations) throws Exception {
        return translate(source, false, transformations);
    }

    private TranslationResult translate(String source, boolean reorder, GraphTransformation... transformations) throws Exception {
        Path file = directory.resolve("Example.java");
        Files.writeString(file, source);
        var adapter = new CpgAdapter(transformations);
        adapter.setReorderingEnabled(reorder);
        return adapter.translate(Set.of(file.toFile()));
    }

    private BinaryOperator assertBranches(String source, String operator, GraphTransformation transformation) throws Exception {
        var graph = translate(source, transformation);
        var expression = SubgraphWalker.INSTANCE.flattenAST(graph).stream().filter(BinaryOperator.class::isInstance).map(BinaryOperator.class::cast)
                .filter(binary -> operator.equals(binary.getOperatorCode())).findFirst().orElseThrow();
        var edges = expression.getLhs().getNextEOGEdges();
        var rightEntry = TransformationUtil.getEntry(expression.getRhs());

        assertEquals(1, expression.getRhs().getNextEOGEdges().size());
        assertSame(expression, expression.getRhs().getNextEOGEdges().getFirst().getEnd());
        assertEquals(2, edges.size());
        for (boolean branch : new boolean[] {false, true}) {
            var edge = edges.stream().filter(candidate -> Boolean.valueOf(branch).equals(candidate.getProperty(Properties.BRANCH))).findFirst()
                    .orElseThrow();
            boolean evaluatesRight = branch == operator.equals("&&");
            assertSame(evaluatesRight ? rightEntry : expression, edge.getEnd());
            assertSame(expression.getLhs(), edge.getStart());
            assertTrue(edge.getEnd().getPrevEOGEdges().stream().anyMatch(incoming -> incoming == edge));
        }
        return expression;
    }

    private void instantiate(GraphTransformation transformation) {
        GraphPattern sourcePattern = transformation.sourcePattern();
        List<Match> maybeMatch = detector.getMatches(sourcePattern);

        assertFalse(maybeMatch.isEmpty());
        Match match = maybeMatch.getFirst();

        TranslationContext ctx = match.get(sourcePattern.getRepresentingNode()).getCtx();
        try (var mutationContext = new GraphMutationContext()) {
            transformation.apply(match, ctx, mutationContext);
        }
    }

}
