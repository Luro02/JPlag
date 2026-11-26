package de.jplag.java_cpg.token;

import static de.jplag.SharedTokenType.FILE_END;
import static de.jplag.java_cpg.token.CpgTokenType.CASE_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.ENUM_DECL_END;
import static de.jplag.java_cpg.token.CpgTokenType.ENUM_ELEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.FIELD_DECL;
import static de.jplag.java_cpg.token.CpgTokenType.LAMBDA_EXPRESSION;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_CALL;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_END;
import static de.jplag.java_cpg.token.CpgTokenType.RETURN;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_STATEMENT;
import static de.jplag.java_cpg.token.TokenTestSupport.parse;
import static de.jplag.java_cpg.token.TokenTestSupport.parseWithTransformations;
import static de.jplag.java_cpg.token.TokenTestSupport.resource;
import static de.jplag.java_cpg.token.TokenTestSupport.sourceOf;
import static de.jplag.java_cpg.token.TokenTestSupport.tokensInMethod;
import static de.jplag.java_cpg.token.TokenTestSupport.uniqueToken;
import static de.jplag.java_cpg.transformation.TransformationRepository.forStatementToWhileStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.moveConstantToOnlyUsingClass;
import static de.jplag.java_cpg.transformation.TransformationRepository.removeOptionalOfCall;
import static de.jplag.testutils.TokenUtils.tokensByFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import de.jplag.Token;
import de.jplag.java_cpg.JavaCpgLanguage;

/**
 * Integration tests for token source locations after normalization.
 */
class TokenLocationTest {
    private static final String CONSTANT_CLASSES = "constantClass/SubmissionA/edu/kit/kastel/sdq/jplag/example/";

    /**
     * Return tokens cover only the keyword, while case prefixes stop before the first child.
     */
    @Test
    void prefixTokensStopAtFirstChild() throws Exception {
        var tokens = parse("TokenPrefixes.java", false);
        var returns = Map.of("compactReturn", "return", "compactStringReturn", "return", "spacedReturn", "return", "multilineReturn", "return");

        for (var entry : returns.entrySet()) {
            Token token = uniqueToken(tokensInMethod(tokens, entry.getKey()), RETURN);
            assertEquals(resource("TokenPrefixes.java").toFile(), token.getFile());
            assertEquals(entry.getValue(), sourceOf(token), entry.getKey());
        }
        assertEquals("case", sourceOf(uniqueToken(tokensInMethod(tokens, "switchValue"), CASE_STATEMENT)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnBoundsDoNotDependOnOptionalNormalization(boolean normalize, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("OptionalReturn.java");
        Files.writeString(file, """
                import java.util.Optional;
                class OptionalReturn {
                    Optional<String> wrap(String value) {
                        return Optional.of(value);
                    }
                }
                """);
        var language = new JavaCpgLanguage().withNormalizations(List.of(removeOptionalOfCall));
        var tokens = language.parse(Set.of(file.toFile()), normalize);
        Token token = uniqueToken(tokens, RETURN);

        assertEquals("return", sourceOf(token));
        assertEquals(List.of(4, 9, 4, 15), List.of(token.getStartLine(), token.getStartColumn(), token.getEndLine(), token.getEndColumn()));
        assertEquals(normalize ? 0L : 1L, tokens.stream().filter(candidate -> candidate.getType() == METHOD_CALL).count());
    }

    /**
     * Closing tokens must stay on the brace, even for empty declarations or after removing the final member.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declarationBoundariesKeepTheirSource(boolean normalize) throws Exception {
        var tokens = parse("DeclarationBoundaries.java", normalize);
        var endings = tokens.stream().filter(token -> token.getType() == RECORD_DECL_END || token.getType() == ENUM_DECL_END).toList();

        assertFalse(endings.isEmpty());
        for (Token token : endings) {
            assertEquals(resource("DeclarationBoundaries.java").toFile(), token.getFile());
            assertEquals("}", sourceOf(token));
        }
        var elements = tokens.stream().filter(token -> token.getType() == ENUM_ELEMENT).toList();
        assertEquals(List.of("FIRST", "SECOND"), elements.stream().map(TokenTestSupport::sourceOf).toList());
        for (Token token : elements) {
            assertEquals(resource("DeclarationBoundaries.java").toFile(), token.getFile());
        }
    }

    /**
     * Lambda tokens must cover the signature and arrow without including the body.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lambdaTokenCoversSignature(boolean normalize) throws Exception {
        var tokens = parse("LambdaExpressions.java", normalize);
        var signatures = Map.of("block", "() ->", "expression", "value ->", "multiline", "(left, right) ->");

        for (var entry : signatures.entrySet()) {
            Token token = uniqueToken(tokensInMethod(tokens, entry.getKey()), LAMBDA_EXPRESSION);
            assertEquals(resource("LambdaExpressions.java").toFile(), token.getFile());
            assertEquals(entry.getValue(), sourceOf(token).stripTrailing(), entry.getKey());
        }
    }

    /**
     * Converted loops must point to the original for-loop header and body.
     */
    @Test
    void convertedLoopKeepsOriginalSourceLocations() throws Exception {
        var tokens = tokensInMethod(parseWithTransformations("for2While/SubmissionA.java", true, forStatementToWhileStatement), "multiply");

        for (var type : List.of(WHILE_STATEMENT, WHILE_BLOCK_START, WHILE_BLOCK_END)) {
            assertEquals(resource("for2While/SubmissionA.java").toFile(), uniqueToken(tokens, type).getFile());
        }
        assertEquals("for (int i = 0, j = 3; ", sourceOf(uniqueToken(tokens, WHILE_STATEMENT)));
        assertEquals("{", sourceOf(uniqueToken(tokens, WHILE_BLOCK_START)));
        assertEquals("}", sourceOf(uniqueToken(tokens, WHILE_BLOCK_END)));
    }

    /**
     * A field moved to UsingClass must keep its location in DefiningClass without changing UsingClass's token locations.
     */
    @Test
    void movedConstantDoesNotDistortDestinationClassLocation() throws Exception {
        String constantsFixture = CONSTANT_CLASSES + "defining/DefiningClass.java";
        String usageFixture = CONSTANT_CLASSES + "using/UsingClass.java";
        var usage = resource(usageFixture).toFile();
        var tokens = parseWithTransformations(List.of(constantsFixture, usageFixture), true, moveConstantToOnlyUsingClass);
        Token classEnd = uniqueToken(tokensByFile(tokens, usage), RECORD_DECL_END);

        assertEquals(usage, classEnd.getFile());
        assertEquals("}", sourceOf(classEnd));

        var destinationTokens = tokens.stream().dropWhile(token -> token.getType() != RECORD_DECL_BEGIN || !token.getFile().equals(usage))
                .takeWhile(token -> token.getType() != FILE_END).toList();
        Token movedField = uniqueToken(destinationTokens, FIELD_DECL);
        assertEquals(resource(constantsFixture).toFile(), movedField.getFile());
        assertEquals("public static final String MY_CONSTANT = ", sourceOf(movedField));
    }
}
