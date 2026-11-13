package de.jplag.java_cpg.token;

import static de.jplag.java_cpg.token.CpgTokenType.ASSIGNMENT;
import static de.jplag.java_cpg.token.CpgTokenType.CASE_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.CATCH_CLAUSE_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.CATCH_CLAUSE_END;
import static de.jplag.java_cpg.token.CpgTokenType.CONSTRUCTOR_CALL;
import static de.jplag.java_cpg.token.CpgTokenType.DEFAULT_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.DO_WHILE_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.DO_WHILE_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.DO_WHILE_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.FINALLY_CLAUSE_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.FINALLY_CLAUSE_END;
import static de.jplag.java_cpg.token.CpgTokenType.IF_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.IF_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_BODY_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_BODY_END;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_CALL;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_PARAM;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_END;
import static de.jplag.java_cpg.token.CpgTokenType.RETURN;
import static de.jplag.java_cpg.token.CpgTokenType.SWITCH_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.SWITCH_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.SWITCH_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.SYNCHRONIZED_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.SYNCHRONIZED_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.SYNCHRONIZED_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.TRY_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.TRY_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.TRY_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.VARIABLE_DECL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import de.jplag.Token;
import de.jplag.TokenType;
import de.jplag.java_cpg.JavaCpgLanguage;

/**
 * Checks control-flow coverage and token ordering.
 */
class TokenTraversalTest {
    /**
     * Calls in catch and finally blocks must not disappear after visiting the try body.
     */
    @Test
    void tryCatchFinallyKeepsAllCalls() throws Exception {
        var tokens = parse("try/Try.java");
        var callLines = tokens.stream().filter(token -> token.getType() == METHOD_CALL && token.getStartLine() >= 14).map(Token::getStartLine)
                .toList();

        assertEquals(List.of(17, 18, 18, 21, 24), callLines);
    }

    /**
     * Each try, catch and finally body must retain its structural markers.
     */
    @Test
    void tryCatchFinallyKeepsStructuralTokens() throws Exception {
        Set<TokenType> structuralTypes = Set.of(TRY_STATEMENT, TRY_BLOCK_START, TRY_BLOCK_END, CATCH_CLAUSE_BEGIN, CATCH_CLAUSE_END,
                FINALLY_CLAUSE_BEGIN, FINALLY_CLAUSE_END);
        var types = parse("try/Try.java").stream().map(Token::getType).filter(structuralTypes::contains).toList();

        assertEquals(List.of(TRY_STATEMENT, TRY_BLOCK_START, TRY_BLOCK_END, CATCH_CLAUSE_BEGIN, CATCH_CLAUSE_END, FINALLY_CLAUSE_BEGIN,
                FINALLY_CLAUSE_END), types);
    }

    @Test
    void tryWithResourcesKeepsInitializationBeforeBody() throws Exception {
        var ordinaryTry = parse("try/Try.java", true).stream().map(Token::getType).toList();
        var resourceTry = parse("try/TryWithResource.java", true).stream().map(Token::getType).toList();
        var initialization = List.of(VARIABLE_DECL, ASSIGNMENT, CONSTRUCTOR_CALL, CONSTRUCTOR_CALL);

        // Resource initialization precedes the body brace only in try-with-resources.
        int ordinaryBody = ordinaryTry.indexOf(TRY_BLOCK_START);
        assertEquals(initialization, ordinaryTry.subList(ordinaryBody + 1, ordinaryBody + 1 + initialization.size()));
        assertEquals(initialization, resourceTry.subList(resourceTry.indexOf(TRY_STATEMENT) + 1, resourceTry.indexOf(TRY_BLOCK_START)));
        assertEquals(1L, ordinaryTry.stream().filter(type -> type == TRY_BLOCK_START).count());
        assertEquals(1L, resourceTry.stream().filter(type -> type == TRY_BLOCK_START).count());
        assertIterableEquals(ordinaryTry.stream().filter(type -> type != TRY_BLOCK_START).toList(),
                resourceTry.stream().filter(type -> type != TRY_BLOCK_START).toList());
    }

    private static Stream<Arguments> controlFlowCases() {
        return Stream.of(
                Arguments.of("Do-while tokens enclose body", 2,
                        List.of(DO_WHILE_STATEMENT, DO_WHILE_BLOCK_START, METHOD_CALL, DO_WHILE_BLOCK_END, METHOD_CALL)),
                Arguments.of("Synchronized tokens enclose body", 9,
                        List.of(SYNCHRONIZED_STATEMENT, SYNCHRONIZED_BLOCK_START, METHOD_CALL, SYNCHRONIZED_BLOCK_END, METHOD_CALL)),
                Arguments.of("Returning switch retains block markers", 16,
                        List.of(SWITCH_STATEMENT, SWITCH_BLOCK_START, CASE_STATEMENT, RETURN, DEFAULT_STATEMENT, RETURN, SWITCH_BLOCK_END)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("controlFlowCases")
    void controlFlowTokensAreOrdered(String description, int declarationLine, List<TokenType> expected) throws Exception {
        for (boolean normalize : List.of(false, true)) {
            assertEquals(expected, controlFlowMethod(declarationLine, normalize).stream().map(Token::getType).toList(), "normalize=" + normalize);
        }
    }

    /**
     * Receiver and argument calls must precede the call that uses their results.
     */
    @Test
    void nestedCallsKeepEvaluationOrder() throws Exception {
        String sourceLine = Files.readAllLines(Path.of("src", "test", "resources", "java", "ControlFlow.java")).get(25);
        var calls = parse("ControlFlow.java").stream().filter(token -> token.getType() == METHOD_CALL && token.getStartLine() == 26)
                .map(token -> sourceLine.substring(token.getStartColumn() - 1, token.getEndColumn() - 1)).toList();

        assertEquals(List.of("trim", "strip", "append", "toString", "println"), calls);
    }

    /**
     * A lambda in the condition must not consume markers belonging to the if body.
     */
    @Test
    void conditionLambdaDoesNotStealBodyMarkers() throws Exception {
        var markers = parse("ControlFlow.java").stream().filter(token -> token.getType() == IF_BLOCK_BEGIN || token.getType() == IF_BLOCK_END)
                .toList();

        assertEquals(List.of(IF_BLOCK_BEGIN, IF_BLOCK_END), markers.stream().map(Token::getType).toList());
        assertEquals(List.of(33, 35), markers.stream().map(Token::getStartLine).toList());
    }

    /**
     * Sorting must not detach statements from their case labels or change fallthrough order.
     */
    @Test
    void normalizedSwitchKeepsFallthroughOrder() throws Exception {
        var plain = controlFlowMethod(41, false);
        var normalized = controlFlowMethod(41, true);

        assertFalse(plain.isEmpty());
        assertEquals(plain.stream().map(Token::getType).toList(), normalized.stream().map(Token::getType).toList());
        assertEquals(plain.stream().map(Token::getStartLine).toList(), normalized.stream().map(Token::getStartLine).toList());
    }

    @Test
    void normalizationKeepsEmptySynchronizedBlock() throws Exception {
        assertEquals(List.of(SYNCHRONIZED_STATEMENT, SYNCHRONIZED_BLOCK_START, SYNCHRONIZED_BLOCK_END),
                controlFlowMethod(55, true).stream().map(Token::getType).toList());
    }

    @Test
    void normalizationKeepsNestedControlFlow() throws Exception {
        var plain = controlFlowMethod(86, false);
        var normalized = controlFlowMethod(86, true);

        assertFalse(plain.isEmpty());
        assertEquals(plain.stream().map(Token::getType).toList(), normalized.stream().map(Token::getType).toList());
        assertEquals(plain.stream().map(Token::getStartLine).toList(), normalized.stream().map(Token::getStartLine).toList());
    }

    private static List<Token> controlFlowMethod(int declarationLine, boolean normalize) throws Exception {
        return methodTokens(parse("ControlFlow.java", normalize), declarationLine);
    }

    private static List<Token> methodTokens(List<Token> tokens, int declarationLine) {
        return tokens.stream().dropWhile(token -> token.getType() != METHOD_DECL_BEGIN || token.getStartLine() != declarationLine).skip(1)
                .takeWhile(token -> token.getType() != METHOD_DECL_BEGIN && token.getType() != RECORD_DECL_END)
                .filter(token -> token.getType() != METHOD_PARAM && token.getType() != METHOD_BODY_BEGIN && token.getType() != METHOD_BODY_END)
                .toList();
    }

    private static List<Token> parse(String fixture) throws Exception {
        return parse(fixture, false);
    }

    private static List<Token> parse(String fixture, boolean normalize) throws Exception {
        Path file = Path.of("src", "test", "resources", "java").resolve(fixture).toAbsolutePath();
        return new JavaCpgLanguage().parse(Set.of(file.toFile()), normalize);
    }
}
