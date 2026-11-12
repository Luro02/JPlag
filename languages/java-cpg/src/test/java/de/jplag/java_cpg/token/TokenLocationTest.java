package de.jplag.java_cpg.token;

import static de.jplag.SharedTokenType.FILE_END;
import static de.jplag.java_cpg.token.CpgTokenType.CATCH_CLAUSE_END;
import static de.jplag.java_cpg.token.CpgTokenType.ENUM_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.ENUM_DECL_END;
import static de.jplag.java_cpg.token.CpgTokenType.ENUM_ELEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.FIELD_DECL;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.LAMBDA_EXPRESSION;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_CALL;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_END;
import static de.jplag.java_cpg.token.CpgTokenType.THROW;
import static de.jplag.java_cpg.transformation.TransformationRepository.forStatementToWhileStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.moveConstantToOnlyUsingClass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import de.fraunhofer.aisec.cpg.TranslationConfiguration;
import de.fraunhofer.aisec.cpg.TranslationManager;
import de.fraunhofer.aisec.cpg.frontends.java.JavaLanguage;
import de.fraunhofer.aisec.cpg.graph.Name;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.statements.CatchClause;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.AssignExpression;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker;
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation;
import de.fraunhofer.aisec.cpg.sarif.Region;
import de.jplag.Token;
import de.jplag.TokenType;
import de.jplag.java_cpg.JavaCpgLanguage;

/**
 * Integration tests for token source locations after normalization.
 */
class TokenLocationTest {
    private static final Path BASE_PATH = Path
            .of("src", "test", "resources", "java", "constantClass", "SubmissionA", "edu", "kit", "kastel", "sdq", "jplag", "example")
            .toAbsolutePath();

    /**
     * Missing locations must not silently produce invalid tokens.
     */
    @Test
    void missingSourceLocationIsRejected() {
        var nodes = List.<Node>of(new AssignExpression()).iterator();
        assertThrows(IllegalStateException.class, () -> CpgNodeListener.tokenIterator(nodes).next());
    }

    @ParameterizedTest
    @CsvSource({"-1, -1, -1, -1", "1, 1, 1, 1", "2, 1, 1, 2"})
    void invalidSourceRegionIsRejected(int startLine, int startColumn, int endLine, int endColumn) {
        var assignment = new AssignExpression();
        Path file = Path.of("src", "test", "resources", "java", "GetterSetter.java").toAbsolutePath();
        assignment.setLocation(new PhysicalLocation(file.toUri(), new Region(startLine, startColumn, endLine, endColumn)));
        var nodes = List.<Node>of(assignment).iterator();

        assertThrows(IllegalStateException.class, () -> CpgNodeListener.tokenIterator(nodes).next());
    }

    /**
     * Prefixes must include complete keywords and stop before the first child, even across line breaks.
     */
    @ParameterizedTest
    @CsvSource({"RETURN, 3, 9, 3, 15", "RETURN, 7, 9, 7, 15", "RETURN, 11, 9, 11, 16", "RETURN, 15, 9, 16, 3", "CASE_STATEMENT, 21, 13, 21, 17"})
    void prefixTokensStopAtFirstChild(CpgTokenType type, int startLine, int startColumn, int endLine, int endColumn) throws Exception {
        Path file = Path.of("src", "test", "resources", "java", "TokenPrefixes.java").toAbsolutePath();
        var language = new JavaCpgLanguage();
        Token token = language.parse(Set.of(file.toFile()), false).stream()
                .filter(candidate -> candidate.getType() == type && candidate.getStartLine() == startLine).findFirst().orElseThrow();

        assertEquals(file.toFile(), token.getFile());
        assertEquals(startColumn, token.getStartColumn());
        assertEquals(endLine, token.getEndLine());
        assertEquals(endColumn, token.getEndColumn());
    }

    /**
     * Closing tokens must stay on the brace, even for empty declarations or after removing the final member.
     */
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void declarationEndTokensPointToClosingBraces(boolean normalize) throws Exception {
        Path file = Path.of("src", "test", "resources", "java", "DeclarationBoundaries.java").toAbsolutePath();
        var language = new JavaCpgLanguage();
        var tokens = language.parse(Set.of(file.toFile()), normalize);
        var endings = tokens.stream().filter(token -> token.getType() == RECORD_DECL_END || token.getType() == ENUM_DECL_END).toList();

        assertEquals(normalize ? 1 : 2, tokens.stream().filter(token -> token.getType() == METHOD_DECL_BEGIN).count());
        assertEquals(4, endings.size());
        for (Token token : endings) {
            assertEquals(file.toFile(), token.getFile());
            assertEquals("}", sourceOf(token));
        }
    }

    /**
     * Throw statements must emit a keyword token with or without an explicit enclosing block.
     */
    @ParameterizedTest
    @CsvSource({"false, IfWithBraces.java", "true, IfWithBraces.java", "false, IfWithoutBraces.java", "true, IfWithoutBraces.java"})
    void throwTokenPointsToKeyword(boolean normalize, String fixture) throws Exception {
        Path file = Path.of("src", "test", "resources", "java", "if", fixture).toAbsolutePath();
        var language = new JavaCpgLanguage();
        var throwTokens = language.parse(Set.of(file.toFile()), normalize).stream().filter(token -> token.getType() == THROW).toList();

        assertEquals(1, throwTokens.size());
        Token token = throwTokens.getFirst();
        assertEquals(file.toFile(), token.getFile());
        assertEquals(8, token.getStartLine());
        assertEquals("throw", sourceOf(token).stripTrailing());
    }

    /**
     * Loop markers must enclose their bodies, not a later standalone block.
     */
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void enhancedForTokensStayWithTheirBodies(boolean normalize) throws Exception {
        Path file = Path.of("src", "test", "resources", "java", "EnhancedFor.java").toAbsolutePath();
        var language = new JavaCpgLanguage();
        var tokens = language.parse(Set.of(file.toFile()), normalize);
        var loopAndCallTokens = tokens.stream().filter(token -> token.getType() == FOR_STATEMENT || token.getType() == FOR_BLOCK_BEGIN
                || token.getType() == FOR_BLOCK_END || token.getType() == METHOD_CALL).toList();

        assertEquals(
                List.of(FOR_STATEMENT, FOR_BLOCK_BEGIN, METHOD_CALL, FOR_BLOCK_END, FOR_STATEMENT, FOR_BLOCK_BEGIN, METHOD_CALL, FOR_BLOCK_END,
                        METHOD_CALL, FOR_STATEMENT, FOR_BLOCK_BEGIN, FOR_STATEMENT, FOR_BLOCK_BEGIN, METHOD_CALL, FOR_BLOCK_END, FOR_BLOCK_END),
                loopAndCallTokens.stream().map(Token::getType).toList());
        assertEquals(List.of(3, 10, 18, 19), tokens.stream().filter(token -> token.getType() == FOR_BLOCK_BEGIN).map(Token::getStartLine).toList());
        assertEquals(List.of(5, 10, 19, 19), tokens.stream().filter(token -> token.getType() == FOR_BLOCK_END).map(Token::getStartLine).toList());
    }

    /**
     * Enum constants must appear in declaration order and point to their names.
     */
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void enumConstantsKeepLocationsAndDeclarationOrder(boolean normalize) throws Exception {
        Path file = Path.of("src", "test", "resources", "java", "DeclarationBoundaries.java").toAbsolutePath();
        var language = new JavaCpgLanguage();
        var tokens = language.parse(Set.of(file.toFile()), normalize);
        var enumTokens = tokens.stream()
                .filter(token -> token.getType() == ENUM_DECL_BEGIN || token.getType() == ENUM_ELEMENT || token.getType() == ENUM_DECL_END).toList();

        assertEquals(List.of(ENUM_DECL_BEGIN, ENUM_DECL_END, ENUM_DECL_BEGIN, ENUM_ELEMENT, ENUM_ELEMENT, ENUM_DECL_END),
                enumTokens.stream().map(Token::getType).toList());

        var elements = enumTokens.stream().filter(token -> token.getType() == ENUM_ELEMENT).toList();
        assertEquals("FIRST", sourceOf(elements.getFirst()));
        assertEquals("SECOND", sourceOf(elements.getLast()));
        for (Token element : elements) {
            assertEquals(file.toFile(), element.getFile());
            assertEquals(18, element.getStartLine());
            assertEquals(18, element.getEndLine());
        }
    }

    /**
     * Lambda tokens must cover the signature and arrow without including the body.
     */
    @ParameterizedTest
    @CsvSource({"false, 3, () ->", "true, 3, () ->", "false, 9, value ->", "true, 9, value ->", "false, 13, '(left, right) ->'",
            "true, 13, '(left, right) ->'"})
    void lambdaTokenCoversSignature(boolean normalize, int line, String expectedSource) throws Exception {
        Path file = Path.of("src", "test", "resources", "java", "LambdaExpressions.java").toAbsolutePath();
        var language = new JavaCpgLanguage();
        Token token = language.parse(Set.of(file.toFile()), normalize).stream()
                .filter(candidate -> candidate.getType() == LAMBDA_EXPRESSION && candidate.getStartLine() == line).findFirst().orElseThrow();

        assertEquals(file.toFile(), token.getFile());
        assertEquals(expectedSource, sourceOf(token).stripTrailing());
    }

    /**
     * Traversal currently skips catch clauses, so exercise their closing token directly.
     */
    @Test
    void catchEndTokenPointsToClosingBrace() throws Exception {
        Path file = Path.of("src", "test", "resources", "java", "try", "Try.java").toAbsolutePath();
        var configuration = new TranslationConfiguration.Builder().sourceLocations(file.toFile()).registerLanguage(new JavaLanguage()).build();
        var graph = TranslationManager.builder().config(configuration).build().analyze().get();
        var catchClause = SubgraphWalker.INSTANCE.flattenAST(graph).stream().filter(CatchClause.class::isInstance).map(CatchClause.class::cast)
                .findFirst().orElseThrow();
        var tokens = new ArrayList<Token>();
        var consumer = new CpgTokenConsumer() {
            @Override
            public void addToken(TokenType type, File source, Region region, int length, Name name) {
                tokens.add(new CpgToken(type, source, region, length, name));
            }
        };

        consumer.addToken(CATCH_CLAUSE_END, catchClause, true);

        assertEquals(1, tokens.size());
        Token token = tokens.getFirst();
        assertEquals(file.toFile(), token.getFile());
        assertEquals(22, token.getStartLine());
        assertEquals("}", sourceOf(token));
    }

    private static String sourceOf(Token token) throws Exception {
        List<String> lines = Files.readAllLines(token.getFile().toPath()).subList(token.getStartLine() - 1, token.getEndLine());
        lines.set(lines.size() - 1, lines.getLast().substring(0, token.getEndColumn() - 1));
        lines.set(0, lines.getFirst().substring(token.getStartColumn() - 1));
        return String.join("\n", lines);
    }

    /**
     * Assignments must cover their source expression, and calls must identify the called method.
     */
    @ParameterizedTest
    @CsvSource({"GetterSetter.java, ASSIGNMENT, 12, this.instance = instance;", "UnusedVariableDeclaration.java, ASSIGNMENT, 7, product += factor1;",
            "VariableDeclaration.java, METHOD_CALL, 6, println"})
    void expressionTokensIdentifyTheirSource(String fixture, CpgTokenType type, int line, String expectedSource) throws Exception {
        Path file = Path.of("src", "test", "resources", "java").resolve(fixture).toAbsolutePath();
        String sourceLine = Files.readAllLines(file).get(line - 1);
        var language = new JavaCpgLanguage();

        for (boolean normalize : List.of(false, true)) {
            Token token = language.parse(Set.of(file.toFile()), normalize).stream().filter(candidate -> candidate.getType() == type).findFirst()
                    .orElseThrow();

            assertEquals(file.toFile(), token.getFile());
            assertEquals(line, token.getStartLine());
            assertEquals(line, token.getEndLine());
            assertEquals(expectedSource, sourceLine.substring(token.getStartColumn() - 1, token.getEndColumn() - 1));
        }
    }

    /**
     * Converted loops must point to the original for-loop header and body.
     */
    @ParameterizedTest
    @CsvSource({"WHILE_STATEMENT, 6, 9, 6, 32", "WHILE_BLOCK_START, 6, 56, 6, 57", "WHILE_BLOCK_END, 9, 9, 9, 10"})
    void convertedLoopKeepsOriginalSourceLocations(CpgTokenType type, int startLine, int startColumn, int endLine, int endColumn) throws Exception {
        Path file = Path.of("src", "test", "resources", "java", "for2While", "SubmissionA.java").toAbsolutePath();
        var language = new JavaCpgLanguage();
        language.resetTransformations();
        language.addTransformation(forStatementToWhileStatement);

        Token token = language.parse(Set.of(file.toFile()), true).stream().filter(candidate -> candidate.getType() == type).findFirst().orElseThrow();

        assertEquals(file.toFile(), token.getFile());
        assertEquals(startLine, token.getStartLine());
        assertEquals(startColumn, token.getStartColumn());
        assertEquals(endLine, token.getEndLine());
        assertEquals(endColumn, token.getEndColumn());
    }

    /**
     * A field moved to UsingClass must keep its location in DefiningClass without changing UsingClass's token locations.
     */
    @Test
    void movedConstantDoesNotDistortDestinationClassLocation() throws Exception {
        Path constants = BASE_PATH.resolve("defining/DefiningClass.java");
        Path usage = BASE_PATH.resolve("using/UsingClass.java");
        var language = new JavaCpgLanguage();
        language.resetTransformations();
        language.addTransformation(moveConstantToOnlyUsingClass);

        var tokens = language.parse(Set.of(constants.toFile(), usage.toFile()), true);
        Token classEnd = tokens.stream().filter(token -> token.getType() == RECORD_DECL_END && token.getFile().equals(usage.toFile())).findFirst()
                .orElseThrow();

        assertEquals(17, classEnd.getStartLine());
        assertEquals(1, classEnd.getStartColumn());
        assertEquals(17, classEnd.getEndLine());
        assertEquals(2, classEnd.getEndColumn());

        Token movedField = tokens.stream().dropWhile(token -> token.getType() != RECORD_DECL_BEGIN || !token.getFile().equals(usage.toFile()))
                .takeWhile(token -> token.getType() != FILE_END).filter(token -> token.getType() == FIELD_DECL).findFirst().orElseThrow();
        assertEquals(constants.toFile(), movedField.getFile());
        assertEquals(8, movedField.getStartLine());
        assertEquals(5, movedField.getStartColumn());
        assertEquals(8, movedField.getEndLine());
        assertEquals(46, movedField.getEndColumn());
    }
}
