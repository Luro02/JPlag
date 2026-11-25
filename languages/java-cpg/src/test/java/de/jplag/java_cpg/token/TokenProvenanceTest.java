package de.jplag.java_cpg.token;

import static de.jplag.SharedTokenType.FILE_END;
import static de.jplag.java_cpg.token.CpgTokenType.ELSE_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.ELSE_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.FIELD_DECL;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.IF_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.IF_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_BODY_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_BODY_END;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_CALL;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_STATEMENT;
import static de.jplag.java_cpg.token.TokenTestSupport.names;
import static de.jplag.java_cpg.token.TokenTestSupport.parseWithTransformations;
import static de.jplag.java_cpg.token.TokenTestSupport.resource;
import static de.jplag.java_cpg.token.TokenTestSupport.sourceOf;
import static de.jplag.java_cpg.token.TokenTestSupport.tokensInMethod;
import static de.jplag.java_cpg.token.TokenTestSupport.uniqueToken;
import static de.jplag.java_cpg.transformation.TransformationRepository.forStatementToWhileStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.ifWithNegatedConditionResolution;
import static de.jplag.java_cpg.transformation.TransformationRepository.moveConstantToOnlyUsingClass;
import static de.jplag.java_cpg.transformation.TransformationRepository.removeAssertion;
import static de.jplag.java_cpg.transformation.TransformationRepository.wrapElseStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.wrapForEachStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.wrapForStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.wrapThenStatement;
import static de.jplag.testutils.TokenUtils.tokensByFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import de.jplag.Token;
import de.jplag.java_cpg.JavaCpgLanguage;

/**
 * Checks transformation associations on surviving tokens, including synthesized and moved code.
 */
class TokenProvenanceTest {
    @Test
    void convertedLoopTokensCarryTransformationNames() throws Exception {
        var tokens = parseWithTransformations("for2While/SubmissionA.java", true, forStatementToWhileStatement);

        for (var type : List.of(WHILE_STATEMENT, WHILE_BLOCK_START, WHILE_BLOCK_END)) {
            assertTrue(names(uniqueToken(tokens, type)).contains(forStatementToWhileStatement.name()));
        }
        var methods = tokens.stream().filter(token -> token.getType() == METHOD_DECL_BEGIN).toList();
        assertFalse(methods.isEmpty());
        assertTrue(methods.stream().allMatch(token -> names(token).isEmpty()));
        assertTrue(names(uniqueToken(tokens, FILE_END)).isEmpty());
    }

    /**
     * Replacing an already wrapped loop must retain the earlier wrapping association.
     */
    @Test
    void replacementRetainsEarlierTransformations() throws Exception {
        Token loop = uniqueToken(parseWithTransformations("verbosity/Compact.java", true, forStatementToWhileStatement), WHILE_STATEMENT);
        Token wrappedBody = uniqueToken(parseWithTransformations("verbosity/Compact.java", false), FOR_BLOCK_BEGIN);

        assertTrue(names(loop).containsAll(List.of(wrapForStatement.name(), forStatementToWhileStatement.name())));
        assertTrue(names(wrappedBody).containsAll(List.of(wrapThenStatement.name(), wrapForStatement.name())));
    }

    /**
     * Wrapping affects the moved body, not adjacent statements or already braced bodies.
     */
    @Test
    void obligatoryWrappingOnlyLabelsAffectedCode() throws Exception {
        var tokens = parseWithTransformations("EnhancedFor.java", false);
        Token bracedBodyCall = uniqueToken(tokensInMethod(tokens, "braced"), METHOD_CALL);
        var unbraced = tokensInMethod(tokens, "unbraced");
        Token unbracedBodyCall = uniqueToken(unbraced.stream().takeWhile(token -> token.getType() != FOR_BLOCK_END).toList(), METHOD_CALL);
        Token adjacentBlockCall = uniqueToken(unbraced.stream().dropWhile(token -> token.getType() != FOR_BLOCK_END).toList(), METHOD_CALL);

        assertTrue(names(bracedBodyCall).isEmpty());
        assertTrue(names(unbracedBodyCall).contains(wrapForEachStatement.name()));
        assertTrue(names(adjacentBlockCall).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"false, true", "true, false", "false, false", "true, true"})
    void swappedBranchesKeepTheirOwnWrappingHistory(boolean bracedThen, boolean bracedElse, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("Branches.java");
        String thenStatement = bracedThen ? "{ System.out.println(1); }" : "System.out.println(1);";
        String elseStatement = bracedElse ? "{ System.out.println(2); }" : "System.out.println(2);";
        Files.writeString(file, "class Branches { static void test(boolean condition) { if (!condition) " + thenStatement + " else " + elseStatement
                + " System.out.println(3); } }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(ifWithNegatedConditionResolution));
        var tokens = language.parse(Set.of(file.toFile()), true);
        String swap = ifWithNegatedConditionResolution.name();
        Set<String> thenHistory = bracedElse ? Set.of(swap) : Set.of(swap, wrapElseStatement.name());
        Set<String> elseHistory = bracedThen ? Set.of(swap) : Set.of(swap, wrapThenStatement.name());

        for (var type : List.of(IF_BLOCK_BEGIN, IF_BLOCK_END)) {
            assertEquals(thenHistory, Set.copyOf(names(uniqueToken(tokens, type))));
        }
        for (var type : List.of(ELSE_BLOCK_BEGIN, ELSE_BLOCK_END)) {
            assertEquals(elseHistory, Set.copyOf(names(uniqueToken(tokens, type))));
        }
        var calls = tokens.stream().filter(token -> token.getType() == METHOD_CALL).toList();
        assertEquals(3, calls.size());
        assertTrue(calls.get(0).getStartColumn() > calls.get(1).getStartColumn());
        assertEquals(thenHistory, Set.copyOf(names(calls.get(0))));
        assertEquals(elseHistory, Set.copyOf(names(calls.get(1))));
        assertTrue(names(calls.get(2)).isEmpty());
    }

    @Test
    void provenanceDoesNotLeakBetweenParses() throws Exception {
        var language = new JavaCpgLanguage();
        language.resetTransformations();
        language.addTransformation(forStatementToWhileStatement);
        var files = Set.of(resource("for2While/SubmissionA.java").toFile());
        var normalized = language.parse(files, true);
        var plain = language.parse(files, false);
        var repeated = language.parse(files, true);

        assertTrue(names(uniqueToken(normalized, WHILE_STATEMENT)).contains(forStatementToWhileStatement.name()));
        uniqueToken(plain, FOR_STATEMENT);
        assertTrue(plain.stream().allMatch(token -> names(token).isEmpty()));
        assertEquals(Set.copyOf(names(uniqueToken(normalized, WHILE_STATEMENT))), Set.copyOf(names(uniqueToken(repeated, WHILE_STATEMENT))));
    }

    @Test
    void assertionRemovalOnlyLabelsTheContainingBlock(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("Assertions.java");
        Files.writeString(file, "class Assertions { static void changed() { System.out.println(1); "
                + "assert true : String.valueOf(2); System.out.println(3); } static void unchanged() { System.out.println(4); } }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(removeAssertion));
        var files = Set.of(file.toFile());
        var normalized = language.parse(files, true);
        var plain = language.parse(files, false);
        var repeated = language.parse(files, true);

        for (var type : List.of(METHOD_BODY_BEGIN, METHOD_BODY_END)) {
            uniqueToken(normalized, token -> token.getType() == type && names(token).contains(removeAssertion.name()));
        }
        assertEquals(2, normalized.stream().filter(token -> names(token).contains(removeAssertion.name())).count());
        var calls = normalized.stream().filter(token -> token.getType() == METHOD_CALL).toList();
        assertEquals(3, calls.size());
        assertTrue(calls.stream().allMatch(token -> names(token).isEmpty()));
        assertTrue(names(uniqueToken(normalized, FILE_END)).isEmpty());
        assertTrue(plain.stream().allMatch(token -> names(token).isEmpty()));
        assertEquals(normalized.stream().map(TokenTestSupport::names).toList(), repeated.stream().map(TokenTestSupport::names).toList());
    }

    @Test
    void movedFieldKeepsItsAssociationAndSourceFile() throws Exception {
        String classes = "constantClass/SubmissionA/edu/kit/kastel/sdq/jplag/example/";
        String constantsFixture = classes + "defining/DefiningClass.java";
        String usageFixture = classes + "using/UsingClass.java";
        var tokens = parseWithTransformations(List.of(constantsFixture, usageFixture), true, moveConstantToOnlyUsingClass);
        Token field = uniqueToken(tokens, token -> token.getType() == FIELD_DECL && sourceOf(token).contains(" MY_CONSTANT ="));
        var unrelatedCalls = tokensByFile(tokens, resource(usageFixture).toFile()).stream().filter(token -> token.getType() == METHOD_CALL).toList();
        var methods = tokens.stream().filter(token -> token.getType() == METHOD_DECL_BEGIN).toList();

        assertEquals(resource(constantsFixture).toFile(), field.getFile());
        assertTrue(names(field).contains(moveConstantToOnlyUsingClass.name()));
        assertFalse(unrelatedCalls.isEmpty());
        assertTrue(unrelatedCalls.stream().allMatch(token -> names(token).isEmpty()));
        assertFalse(methods.isEmpty());
        assertTrue(methods.stream().allMatch(token -> names(token).isEmpty()));
    }

}
