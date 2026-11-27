package de.jplag.java_cpg.token;

import static de.jplag.java_cpg.token.CpgTokenType.ELSE_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.IF_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_CALL;
import static de.jplag.java_cpg.token.CpgTokenType.VARIABLE_DECL;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_STATEMENT;
import static de.jplag.java_cpg.token.TokenTestSupport.resource;
import static de.jplag.java_cpg.token.TokenTestSupport.sourceOf;
import static de.jplag.java_cpg.token.TokenTestSupport.tokensInMethod;
import static de.jplag.java_cpg.token.TokenTestSupport.uniqueToken;
import static de.jplag.java_cpg.transformation.TransformationRepository.forStatementToWhileStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.ifWithNegatedConditionResolution;
import static de.jplag.java_cpg.transformation.TransformationRepository.removeOptionalOfCall;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jplag.IdentityAwareToken;
import de.jplag.Token;
import de.jplag.java_cpg.JavaCpgLanguage;

class TokenIdentityTest {
    @Test
    void independentParsesAndReversedFilesKeepIdentities() throws Exception {
        var files = List.of(resource("EnhancedFor.java").toFile(), resource("TokenPrefixes.java").toFile());
        var language = new JavaCpgLanguage().withNormalizations(List.of());
        var first = trackedTokensByIdentity(language.parse(new LinkedHashSet<>(files), false));
        var repeated = trackedTokensByIdentity(language.parse(new LinkedHashSet<>(files), false));
        var reversed = trackedTokensByIdentity(language.parse(new LinkedHashSet<>(files.reversed()), false));

        assertFalse(first.isEmpty());
        assertEquals(first.keySet(), repeated.keySet());
        assertEquals(first.keySet(), reversed.keySet());
        for (var entry : first.entrySet()) {
            assertEquals(sourceOf(entry.getValue()), sourceOf(repeated.get(entry.getKey())));
            assertEquals(sourceOf(entry.getValue()), sourceOf(reversed.get(entry.getKey())));
            assertEquals(entry.getValue().getType(), reversed.get(entry.getKey()).getType());
        }
    }

    @Test
    void forToWhileContinuesHeaderAndBodyTokensWithoutCollisions() throws Exception {
        var language = new JavaCpgLanguage().withNormalizations(List.of(forStatementToWhileStatement));
        var files = Set.of(resource("for2While/SubmissionA.java").toFile());
        var baseline = tokensInMethod(language.parse(files, false), "multiply");
        var normalized = tokensInMethod(language.parse(files, true), "multiply");

        assertEquals(identity(uniqueToken(baseline, FOR_STATEMENT)), identity(uniqueToken(normalized, WHILE_STATEMENT)));
        assertEquals(identity(uniqueToken(baseline, FOR_BLOCK_BEGIN)), identity(uniqueToken(normalized, WHILE_BLOCK_START)));
        assertEquals(identity(uniqueToken(baseline, FOR_BLOCK_END)), identity(uniqueToken(normalized, WHILE_BLOCK_END)));
        assertNotEquals(identity(uniqueToken(normalized, WHILE_BLOCK_START)), identity(uniqueToken(normalized, WHILE_BLOCK_END)));
        assertDoesNotThrow(() -> trackedTokensByIdentity(normalized));
    }

    @Test
    void optionalUnwrappingPreservesInnerCallAndDeclarationIdentities(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("OptionalCalls.java");
        Files.writeString(file, """
                import java.util.Optional;
                class OptionalCalls {
                    Optional<String> wrap(String value) {
                        Optional<String> result = Optional.of(value.trim());
                        return result;
                    }
                }
                """);
        var language = new JavaCpgLanguage().withNormalizations(List.of(removeOptionalOfCall));
        var baseline = language.parse(Set.of(file.toFile()), false);
        var normalized = language.parse(Set.of(file.toFile()), true);
        Token originalCall = uniqueToken(baseline, token -> token.getType() == METHOD_CALL && sourceOf(token).contains("trim"));
        Token originalWrapper = uniqueToken(baseline, token -> token.getType() == METHOD_CALL && token != originalCall);
        Token survivingCall = uniqueToken(normalized, METHOD_CALL);
        Token originalDeclaration = uniqueToken(baseline, VARIABLE_DECL);
        Token normalizedDeclaration = uniqueToken(normalized, VARIABLE_DECL);

        assertEquals(identity(originalCall), identity(survivingCall));
        assertEquals(identity(originalDeclaration), identity(normalizedDeclaration));
        assertNotEquals(identity(originalWrapper), identity(survivingCall));
    }

    @Test
    void swappedBranchesKeepTheirOwnOpeningIdentities(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("Branches.java");
        Files.writeString(file, """
                class Branches {
                    void run(boolean condition) {
                        if (!condition) { System.out.println(1); }
                        else { System.out.println(2); }
                    }
                }
                """);
        var language = new JavaCpgLanguage().withNormalizations(List.of(ifWithNegatedConditionResolution));
        var baseline = language.parse(Set.of(file.toFile()), false);
        var normalized = language.parse(Set.of(file.toFile()), true);

        assertEquals(identity(uniqueToken(baseline, IF_BLOCK_BEGIN)), identity(uniqueToken(normalized, ELSE_BLOCK_BEGIN)));
        assertEquals(identity(uniqueToken(baseline, ELSE_BLOCK_BEGIN)), identity(uniqueToken(normalized, IF_BLOCK_BEGIN)));
        assertNotEquals(identity(uniqueToken(normalized, IF_BLOCK_BEGIN)), identity(uniqueToken(normalized, ELSE_BLOCK_BEGIN)));
    }

    private static String identity(Token token) {
        return ((IdentityAwareToken) token).identity().orElseThrow();
    }

    private static Map<String, Token> trackedTokensByIdentity(Collection<Token> tokens) {
        return tokens.stream().filter(token -> token instanceof IdentityAwareToken aware && aware.identity().isPresent())
                .collect(Collectors.toMap(TokenIdentityTest::identity, Function.identity()));
    }
}
