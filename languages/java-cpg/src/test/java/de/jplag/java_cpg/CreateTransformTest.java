package de.jplag.java_cpg;

import static de.jplag.java_cpg.transformation.TransformationRepository.forStatementToWhileStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.inlineSingleUseVariable;
import static de.jplag.java_cpg.transformation.TransformationRepository.removeAssertion;
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
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
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
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.declarations.FunctionDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.VariableDeclaration;
import de.fraunhofer.aisec.cpg.graph.edge.Properties;
import de.fraunhofer.aisec.cpg.graph.statements.AssertStatement;
import de.fraunhofer.aisec.cpg.graph.statements.DeclarationStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ForStatement;
import de.fraunhofer.aisec.cpg.graph.statements.IfStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ReturnStatement;
import de.fraunhofer.aisec.cpg.graph.statements.WhileStatement;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.BinaryOperator;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.CallExpression;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void optionalRemovalPreservesTheJoinAfterAnIf(boolean reorder) throws Exception {
        var graph = translate("""
                import java.util.Optional;
                class Example {
                    static boolean test(boolean condition, Optional<Boolean> wrapped) {
                        if (condition) { System.out.println("then"); }
                        return wrapped.get();
                    }
                }
                """, reorder, removeOptionalGetCall);
        var function = testFunction(graph);
        var nodes = SubgraphWalker.INSTANCE.flattenAST(function);
        var conditional = nodes.stream().filter(IfStatement.class::isInstance).map(IfStatement.class::cast).findFirst().orElseThrow();
        var returned = nodes.stream().filter(ReturnStatement.class::isInstance).map(ReturnStatement.class::cast).findFirst().orElseThrow();
        var value = assertInstanceOf(Reference.class, returned.getReturnValue());
        var thenExit = TransformationUtil.getEogBorders(conditional.getThenStatement()).getExits().getFirst();

        assertEquals("wrapped", value.getName().getLocalName());
        assertEquals(2, value.getPrevEOGEdges().size());
        assertTrue(value.getPrevEOGEdges().stream().anyMatch(edge -> edge.getStart() == conditional));
        assertTrue(value.getPrevEOGEdges().stream().anyMatch(edge -> edge.getStart() == thenExit));
        assertTrue(value.getNextEOGEdges().stream().anyMatch(edge -> edge.getEnd() == returned));
        assertEogIntegrity(function);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "if (condition) { System.out.println(\"then\"); }",
            "if (condition) { System.out.println(\"then\"); } else { System.out.println(\"else\"); }"})
    void convertedLoopsPreserveStatementsAndJoinsDuringDfgNormalization(String precedingStatement) throws Exception {
        String source = """
                class Example {
                    static void test(boolean condition, int limit) {
                        %s
                        for (int index = 0; index < limit; index++) { System.out.println("loop"); }
                        System.out.println("after");
                    }
                }
                """.formatted(precedingStatement);
        var before = testFunction(translate(source, false, forStatementToWhileStatement));
        var after = testFunction(translate(source, true, forStatementToWhileStatement));
        var nodes = SubgraphWalker.INSTANCE.flattenAST(after);
        var loop = nodes.stream().filter(WhileStatement.class::isInstance).map(WhileStatement.class::cast).findFirst().orElseThrow();
        var surrounding = nodes.stream().filter(Block.class::isInstance).map(Block.class::cast)
                .filter(block -> block.getStatements().stream().anyMatch(statement -> statement == loop)).findFirst().orElseThrow();
        var initialization = surrounding.getStatements().stream().filter(DeclarationStatement.class::isInstance).findFirst().orElseThrow();

        assertEquals(callsIn(before), callsIn(after));
        assertEogIntegrity(before);
        assertFalse(nodes.stream().anyMatch(ForStatement.class::isInstance));
        assertEogIntegrity(after);
        assertTrue(surrounding.getStatements().indexOf(initialization) < surrounding.getStatements().indexOf(loop),
                "The loop initializer must remain before the converted loop");
        var condition = assertInstanceOf(BinaryOperator.class, loop.getCondition());
        assertTrue(initialization.getNextDFG().stream().anyMatch(node -> node == loop),
                "The initializer must remain a dependency of the converted loop");
        assertTrue(TransformationUtil.isEogSuccessor(initialization, TransformationUtil.getEntry(condition)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"assert checked > 0;", "assert checked > 0 : detail;",
            "assert System.nanoTime() > checked : System.nanoTime() + detail;",
            "assert checked > 0 : input > 0 ? detail : 0;", "assert checked > 0 : input > 0 && detail > 0;"})
    void dfgNormalizationPreservesAssertionsAndTheirDependencies(String assertion) throws Exception {
        String source = """
                class Example {
                    static void test(int input) {
                        int checked = input + 1;
                        int detail = input + 2;
                        %s
                        System.out.println("after");
                    }
                }
                """.formatted(assertion);
        var before = testFunction(translate(source));
        var after = testFunction(translate(source, true));
        var nodes = SubgraphWalker.INSTANCE.flattenAST(after);
        var retained = nodes.stream().filter(AssertStatement.class::isInstance).map(AssertStatement.class::cast).findFirst().orElseThrow();
        var body = assertInstanceOf(Block.class, after.getBody());

        assertEquals(callsIn(before), callsIn(after));
        for (String name : assertion.contains("detail") ? List.of("checked", "detail") : List.of("checked")) {
            var reference = SubgraphWalker.INSTANCE.flattenAST(retained).stream().filter(Reference.class::isInstance).map(Reference.class::cast)
                    .filter(candidate -> name.equals(candidate.getName().getLocalName())).findFirst().orElseThrow();
            var declaration = assertInstanceOf(VariableDeclaration.class, reference.getRefersTo());
            assertTrue(nodes.stream().anyMatch(candidate -> candidate == declaration), () -> "Missing assertion dependency: " + name);
            var definition = body.getStatements().stream().filter(DeclarationStatement.class::isInstance).map(DeclarationStatement.class::cast)
                    .filter(statement -> statement.getDeclarations().contains(declaration)).findFirst().orElseThrow();
            assertTrue(body.getStatements().indexOf(definition) < body.getStatements().indexOf(retained));
        }
        assertEogIntegrity(after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"if (condition) { assert true; } else { System.out.println(1); }",
            "if (condition) { System.out.println(1); } else { assert true; }", "while (System.nanoTime() > 0) { assert true; }"})
    void assertionRemovalAllowsDfgNormalizationOfEmptyBranches(String statement) throws Exception {
        String source = "class Example { static void test(boolean condition) { " + statement + " } }";
        var before = testFunction(translate(source, false, removeAssertion));
        var after = testFunction(translate(source, true, removeAssertion));

        assertFalse(SubgraphWalker.INSTANCE.flattenAST(after).stream().anyMatch(AssertStatement.class::isInstance));
        assertEquals(callsIn(before), callsIn(after));
        assertEogIntegrity(after);
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

    private static FunctionDeclaration testFunction(TranslationResult graph) {
        return SubgraphWalker.INSTANCE.flattenAST(graph).stream().filter(FunctionDeclaration.class::isInstance).map(FunctionDeclaration.class::cast)
                .filter(function -> "test".equals(function.getName().getLocalName())).findFirst().orElseThrow();
    }

    private static List<String> callsIn(FunctionDeclaration function) {
        return SubgraphWalker.INSTANCE.flattenAST(function).stream().filter(CallExpression.class::isInstance).map(Node::getCode).toList();
    }

    private static void assertEogIntegrity(FunctionDeclaration function) {
        List<Node> nodes = SubgraphWalker.INSTANCE.flattenAST(function);
        Set<Node> astNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        astNodes.addAll(nodes);
        Set<Node> reachable = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Node>();
        pending.add(function);
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (reachable.add(node)) {
                pending.addAll(node.getNextEOG());
            }
        }

        for (Node node : nodes) {
            for (var edge : node.getNextEOGEdges()) {
                assertSame(node, edge.getStart());
                assertTrue(astNodes.contains(edge.getEnd()));
                assertTrue(edge.getEnd().getPrevEOGEdges().stream().anyMatch(incoming -> incoming == edge));
            }
            for (var edge : node.getPrevEOGEdges()) {
                assertSame(node, edge.getEnd());
                assertTrue(astNodes.contains(edge.getStart()));
                assertTrue(edge.getStart().getNextEOGEdges().stream().anyMatch(outgoing -> outgoing == edge));
            }
            if (node instanceof CallExpression || node instanceof ReturnStatement) {
                assertTrue(reachable.contains(node), () -> "Unreachable from the function's EOG entry: " + node.getCode());
            }
        }
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
