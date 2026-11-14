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
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.IF_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.IF_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_CALL;
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
import static de.jplag.java_cpg.token.TokenTestSupport.parse;
import static de.jplag.java_cpg.token.TokenTestSupport.sourceOf;
import static de.jplag.java_cpg.token.TokenTestSupport.tokensInMethod;
import static de.jplag.java_cpg.token.TokenTestSupport.types;
import static de.jplag.java_cpg.token.TokenTestSupport.uniqueToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import de.jplag.Token;
import de.jplag.TokenType;

/**
 * Checks control-flow coverage and token ordering.
 */
class TokenTraversalTest {
    /**
     * Catch and finally bodies must survive traversal of the try body, including their calls and structural markers.
     */
    @Test
    void tryCatchFinallyKeepsCallsAndStructuralTokens() throws Exception {
        var tokens = tokensInMethod(parse("try/Try.java", false), "load");
        var calls = tokens.stream().filter(token -> token.getType() == METHOD_CALL).map(TokenTestSupport::sourceOf).toList();

        assertEquals(List.of("hasNext", "nextLine", "println", "printStackTrace", "close"), calls);

        Set<TokenType> structuralTypes = Set.of(TRY_STATEMENT, TRY_BLOCK_START, TRY_BLOCK_END, CATCH_CLAUSE_BEGIN, CATCH_CLAUSE_END,
                FINALLY_CLAUSE_BEGIN, FINALLY_CLAUSE_END);
        assertEquals(List.of(TRY_STATEMENT, TRY_BLOCK_START, TRY_BLOCK_END, CATCH_CLAUSE_BEGIN, CATCH_CLAUSE_END, FINALLY_CLAUSE_BEGIN,
                FINALLY_CLAUSE_END), types(tokens).stream().filter(structuralTypes::contains).toList());
    }

    @Test
    void tryWithResourcesKeepsInitializationBeforeBody() throws Exception {
        var ordinaryTry = types(tokensInMethod(parse("try/Try.java", true), "load"));
        var resourceTry = types(tokensInMethod(parse("try/TryWithResource.java", true), "load"));
        var initialization = List.of(VARIABLE_DECL, ASSIGNMENT, CONSTRUCTOR_CALL, CONSTRUCTOR_CALL);

        // Resource initialization precedes the body brace only in try-with-resources.
        int ordinaryBody = ordinaryTry.indexOf(TRY_BLOCK_START);
        assertEquals(initialization, ordinaryTry.subList(ordinaryBody + 1, ordinaryBody + 1 + initialization.size()));
        assertEquals(initialization, resourceTry.subList(resourceTry.indexOf(TRY_STATEMENT) + 1, resourceTry.indexOf(TRY_BLOCK_START)));
        assertEquals(ordinaryTry.stream().filter(type -> type != TRY_BLOCK_START).toList(),
                resourceTry.stream().filter(type -> type != TRY_BLOCK_START).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void controlFlowTokensAreOrdered(boolean normalize) throws Exception {
        var tokens = parse("ControlFlow.java", normalize);

        assertEquals(List.of(DO_WHILE_STATEMENT, DO_WHILE_BLOCK_START, METHOD_CALL, DO_WHILE_BLOCK_END, METHOD_CALL),
                types(tokensInMethod(tokens, "doWhileLoop")), "Do-while tokens enclose body");
        assertEquals(List.of(SYNCHRONIZED_STATEMENT, SYNCHRONIZED_BLOCK_START, METHOD_CALL, SYNCHRONIZED_BLOCK_END, METHOD_CALL),
                types(tokensInMethod(tokens, "synchronizedBlock")), "Synchronized tokens enclose body");
        assertEquals(List.of(SWITCH_STATEMENT, SWITCH_BLOCK_START, CASE_STATEMENT, RETURN, DEFAULT_STATEMENT, RETURN, SWITCH_BLOCK_END),
                types(tokensInMethod(tokens, "switchReturns")), "Returning switch retains block markers");
    }

    /**
     * Receiver and argument calls must precede the call that uses their results.
     */
    @Test
    void nestedCallsKeepEvaluationOrder() throws Exception {
        var tokens = tokensInMethod(parse("ControlFlow.java", false), "nestedCalls");
        var calls = tokens.stream().filter(token -> token.getType() == METHOD_CALL).map(TokenTestSupport::sourceOf).toList();

        assertEquals(List.of("trim", "strip", "append", "toString", "println"), calls);
    }

    /**
     * A lambda in the condition must not consume markers belonging to the if body.
     */
    @Test
    void conditionLambdaDoesNotStealBodyMarkers() throws Exception {
        var tokens = tokensInMethod(parse("ControlFlow.java", false), "conditionWithLambda");
        var bodyAndFollowing = tokens.stream().dropWhile(token -> token.getType() != IF_BLOCK_BEGIN).toList();

        assertEquals(List.of(IF_BLOCK_BEGIN, METHOD_CALL, IF_BLOCK_END, METHOD_CALL), types(bodyAndFollowing));
        assertEquals("{", sourceOf(uniqueToken(tokens, IF_BLOCK_BEGIN)));
        assertEquals("}", sourceOf(uniqueToken(tokens, IF_BLOCK_END)));
    }

    /**
     * Normalization must preserve fallthrough order and the boundaries of empty or nested control flow.
     */
    @Test
    void normalizationKeepsControlFlowStructure() throws Exception {
        var plain = parse("ControlFlow.java", false);
        var normalized = parse("ControlFlow.java", true);

        for (String methodName : List.of("switchFallthrough", "emptySynchronized", "nestedControlFlow")) {
            var originalMethod = tokensInMethod(plain, methodName);
            var normalizedMethod = tokensInMethod(normalized, methodName);
            assertFalse(originalMethod.isEmpty(), methodName);
            assertEquals(types(originalMethod), types(normalizedMethod), methodName);
            // Call tokens only cover names, so source text alone cannot detect reordered calls to println.
            assertEquals(originalMethod.stream().map(Token::getStartLine).toList(), normalizedMethod.stream().map(Token::getStartLine).toList(),
                    methodName);
        }
        assertEquals(List.of(SYNCHRONIZED_STATEMENT, SYNCHRONIZED_BLOCK_START, SYNCHRONIZED_BLOCK_END),
                types(tokensInMethod(normalized, "emptySynchronized")));
    }

    /**
     * Loop markers must enclose their bodies, not a later standalone block.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enhancedForTokensStayWithTheirBodies(boolean normalize) throws Exception {
        var tokens = parse("EnhancedFor.java", normalize);
        Set<TokenType> loopAndCallTypes = Set.of(FOR_STATEMENT, FOR_BLOCK_BEGIN, FOR_BLOCK_END, METHOD_CALL);
        var braced = tokensInMethod(tokens, "braced");

        assertEquals(List.of(FOR_STATEMENT, FOR_BLOCK_BEGIN, METHOD_CALL, FOR_BLOCK_END),
                types(braced).stream().filter(loopAndCallTypes::contains).toList());
        assertEquals("{", sourceOf(uniqueToken(braced, FOR_BLOCK_BEGIN)));
        assertEquals("}", sourceOf(uniqueToken(braced, FOR_BLOCK_END)));
        assertEquals(List.of(FOR_STATEMENT, FOR_BLOCK_BEGIN, METHOD_CALL, FOR_BLOCK_END, METHOD_CALL),
                types(tokensInMethod(tokens, "unbraced")).stream().filter(loopAndCallTypes::contains).toList());
        assertEquals(List.of(FOR_STATEMENT, FOR_BLOCK_BEGIN, FOR_STATEMENT, FOR_BLOCK_BEGIN, METHOD_CALL, FOR_BLOCK_END, FOR_BLOCK_END),
                types(tokensInMethod(tokens, "nested")).stream().filter(loopAndCallTypes::contains).toList());
    }
}
