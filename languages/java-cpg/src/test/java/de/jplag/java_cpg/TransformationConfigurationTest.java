package de.jplag.java_cpg;

import static de.jplag.SharedTokenType.FILE_END;
import static de.jplag.java_cpg.CpgNormalization.DFG_NORMALIZATION;
import static de.jplag.java_cpg.token.CpgTokenType.ASSERT_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.ENUM_ELEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.VARIABLE_DECL;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_STATEMENT;
import static de.jplag.java_cpg.transformation.TransformationRepository.forStatementToWhileStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.ifWithNegatedConditionResolution;
import static de.jplag.java_cpg.transformation.TransformationRepository.inlineSingleUseVariable;
import static de.jplag.java_cpg.transformation.TransformationRepository.removeAssertion;
import static de.jplag.java_cpg.transformation.TransformationRepository.removeEmptyRecord;
import static de.jplag.java_cpg.transformation.TransformationRepository.removeImplicitStandardConstructor;
import static de.jplag.java_cpg.transformation.TransformationRepository.removeUnsupportedMethod;
import static de.jplag.java_cpg.transformation.TransformationRepository.wrapThenStatement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import de.jplag.TokenType;
import de.jplag.java_cpg.passes.TransformationPassConfiguration;
import de.jplag.java_cpg.transformation.GraphTransformation.ExecutionPhase;
import de.jplag.java_cpg.transformation.operations.GraphMutationContext;

class TransformationConfigurationTest {
    @TempDir
    private Path directory;

    private Path source(String name, String method) throws Exception {
        Path path = directory.resolve(name + ".java");
        Files.writeString(path, "class " + name + " { " + method + " }");
        return path;
    }

    private List<TokenType> tokens(JavaCpgLanguage language, Path source, boolean normalize) throws Exception {
        return language.parse(Set.of(source.toFile()), normalize).stream().map(token -> token.getType()).toList();
    }

    private Path loop() throws Exception {
        return source("Loop", "static int sum(int[] a) { int sum=0; for(int i=0;i<a.length;i++) { sum+=a[i]; } return sum; }");
    }

    @Test
    void normalizationSelectionDoesNotDependOnPreviousParses() throws Exception {
        Path file = loop();
        var language = new JavaCpgLanguage();
        var normalized = tokens(new JavaCpgLanguage(), file, true);
        var plain = tokens(new JavaCpgLanguage(), file, false);
        assertFalse(normalized.equals(plain), "Fixture must expose loop conversion");
        assertEquals(normalized, tokens(language, file, true));
        assertEquals(plain, tokens(language, file, false));
        assertEquals(normalized, tokens(language, file, true));
        var initiallyPlain = new JavaCpgLanguage();
        assertEquals(plain, tokens(initiallyPlain, file, false));
        assertEquals(normalized, tokens(initiallyPlain, file, true));
    }

    @Test
    void constructingAndConfiguringOtherInstancesDoesNotChangeSelection() throws Exception {
        Path file = loop();
        var first = new JavaCpgLanguage();
        first.resetTransformations();
        var obligatoryOnly = tokens(first, file, true);
        var second = new JavaCpgLanguage();
        var allRules = tokens(second, file, true);
        assertFalse(obligatoryOnly.equals(allRules));
        assertEquals(obligatoryOnly, tokens(first, file, true));
        first.addTransformation(forStatementToWhileStatement);
        assertEquals(allRules, tokens(first, file, true));
        first.resetTransformations();
        assertEquals(allRules, tokens(second, file, true));
        assertEquals(obligatoryOnly, tokens(first, file, true));
    }

    @Test
    void obligatoryWrappingIsAppliedWithAndWithoutNormalization() throws Exception {
        Path braced = source("Braced", "static int pick(boolean b) { if(b) { return 1; } else { return 2; } }");
        Path bare = source("Bare", "static int pick(boolean b) { if(b) return 1; else return 2; }");
        var language = new JavaCpgLanguage();
        var obligatoryOnly = language.withNormalizations(List.of());
        for (boolean normalize : List.of(true, false)) {
            assertEquals(tokens(language, braced, normalize), tokens(language, bare, normalize));
            assertEquals(tokens(obligatoryOnly, braced, normalize), tokens(obligatoryOnly, bare, normalize));
        }
    }

    @Test
    void selectedNormalizationsAreIndependentOfOtherInstances() throws Exception {
        Path file = loop();
        var original = new JavaCpgLanguage();
        var loopConversion = original.withNormalizations(List.of(forStatementToWhileStatement));
        var obligatoryOnly = loopConversion.withNormalizations(List.of());

        assertTrue(tokens(original, file, true).contains(WHILE_STATEMENT));
        original.resetTransformations();
        assertTrue(tokens(original, file, true).contains(FOR_STATEMENT));
        assertTrue(tokens(loopConversion, file, true).contains(WHILE_STATEMENT));
        assertTrue(tokens(obligatoryOnly, file, true).contains(FOR_STATEMENT));
    }

    @Test
    void normalizationsAreAppliedInTheRequestedOrder() throws Exception {
        Path file = directory.resolve("Unsupported.java");
        Files.writeString(file, "package example; class Unsupported { static void fail() { throw new UnsupportedOperationException(); } }");
        var language = new JavaCpgLanguage();
        var cleanupAfterRemoval = language.withNormalizations(List.of(removeImplicitStandardConstructor, removeUnsupportedMethod, removeEmptyRecord));
        var cleanupBeforeRemoval = language
                .withNormalizations(List.of(removeImplicitStandardConstructor, removeEmptyRecord, removeUnsupportedMethod));

        assertFalse(tokens(cleanupAfterRemoval, file, true).contains(RECORD_DECL_BEGIN));
        assertTrue(tokens(cleanupBeforeRemoval, file, true).contains(RECORD_DECL_BEGIN));
    }

    @Test
    void dfgNormalizationIsSelectableAndRespectsTheNormalizationFlag() throws Exception {
        Path file = source("Unused", "static int value() { int unused = 1; return 2; }");
        var language = new JavaCpgLanguage();
        var obligatoryOnly = language.withNormalizations(List.of());
        var dfgOnly = obligatoryOnly.withNormalizations(List.of(DFG_NORMALIZATION));

        var plain = tokens(obligatoryOnly, file, true);
        assertTrue(plain.contains(VARIABLE_DECL));
        assertFalse(tokens(dfgOnly, file, true).contains(VARIABLE_DECL));
        assertEquals(plain, tokens(dfgOnly, file, false));
    }

    @Test
    void convertedLoopsMatchExplicitWhileLoopsWithDfgNormalization() throws Exception {
        Path forLoop = source("ForLoop", "static void sample(int limit) { "
                + "for (int index = 0; index < limit; index++) { System.out.println(index); } System.out.println(0); }");
        Path whileLoop = source("WhileLoop", "static void sample(int limit) { int index = 0; "
                + "while (index < limit) { System.out.println(index); index++; } System.out.println(0); }");
        var language = new JavaCpgLanguage();
        var conversionOnly = language.withNormalizations(List.of(forStatementToWhileStatement));
        var conversionAndDfg = language.withNormalizations(List.of(forStatementToWhileStatement, DFG_NORMALIZATION));

        assertEquals(tokens(conversionOnly, whileLoop, true), tokens(conversionOnly, forLoop, true));
        assertEquals(tokens(conversionAndDfg, whileLoop, true), tokens(conversionAndDfg, forLoop, true));
    }

    @Test
    void assertionRemovalIsSelectableAndRespectsTheNormalizationFlag() throws Exception {
        Path file = source("Assertions", "static void sample() { int checked = 1; int message = 2; System.out.println(0); "
                + "assert checked > 0; assert checked > 0 : String.valueOf(message); System.out.println(3); }");
        Path withoutAssertions = source("WithoutAssertions",
                "static void sample() { int checked = 1; int message = 2; System.out.println(0); System.out.println(3); }");
        var language = new JavaCpgLanguage();
        var obligatoryOnly = language.withNormalizations(List.of());
        var removalOnly = language.withNormalizations(List.of(removeAssertion));

        assertTrue(language.availableNormalizations().contains(removeAssertion));
        assertEquals(ExecutionPhase.AST_TRANSFORM, removeAssertion.phase());
        var plain = tokens(obligatoryOnly, file, true);
        assertEquals(2, plain.stream().filter(type -> type == ASSERT_STATEMENT).count());
        assertEquals(plain, tokens(removalOnly, file, false));
        var removed = tokens(removalOnly, file, true);
        assertFalse(removed.contains(ASSERT_STATEMENT));
        assertEquals(tokens(obligatoryOnly, withoutAssertions, true), removed);
        assertTrue(removed.contains(VARIABLE_DECL));
    }

    @Test
    void assertionRemovalPrecedesDfgCleanupOfAssertionOnlyDependencies() throws Exception {
        Path file = source("AssertionDependencies", "static int sample() { int checked = 1; int message = 2; "
                + "assert checked > 0 : message; System.out.println(3); return 4; }");
        Path withoutDependencies = source("WithoutDependencies", "static int sample() { System.out.println(3); return 4; }");
        var language = new JavaCpgLanguage();
        var dfgOnly = language.withNormalizations(List.of(DFG_NORMALIZATION));
        var removalAndDfg = language.withNormalizations(List.of(removeAssertion, DFG_NORMALIZATION));
        var plain = tokens(language.withNormalizations(List.of()), file, true);
        var removed = tokens(removalAndDfg, file, true);

        assertEquals(plain, tokens(dfgOnly, file, true));
        assertFalse(removed.contains(ASSERT_STATEMENT));
        assertFalse(removed.contains(VARIABLE_DECL));
        assertEquals(tokens(language.withNormalizations(List.of()), withoutDependencies, true), removed);
        assertEquals(plain, tokens(removalAndDfg, file, false));
    }

    @Test
    void dfgNormalizationRemovesNestedUnusedDeclarations() throws Exception {
        Path nested = source("NestedUnused", "static void report() { { int unused = 1; } System.out.println(1); }");
        Path withoutUnused = source("WithoutUnused", "static void report() { System.out.println(1); }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(DFG_NORMALIZATION));

        assertTrue(tokens(language, nested, false).contains(VARIABLE_DECL));
        assertEquals(tokens(language, withoutUnused, true), tokens(language, nested, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"int[] unused = new int[dimension(size)];", "int[][] unused = new int[1][dimension(size)];"})
    void dfgNormalizationPreservesCallsInArrayDimensions(String allocation) throws Exception {
        Path file = source("ArrayDimension",
                "static int dimension(int size) { return size; } static void report() { int size = 2; " + allocation + " }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(DFG_NORMALIZATION));

        assertEquals(tokens(language, file, false), tokens(language, file, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"int[] unused = new int[] { next(first), next(second) };", "int[] unused = { next(first), next(second) };",
            "int[][] unused = { { next(first) }, { next(second) } };"})
    void dfgNormalizationPreservesCallsInArrayInitializers(String allocation) throws Exception {
        Path file = source("ArrayInitializer",
                "static int next(int value) { return value; } static void report() { int first = 1; int second = 2; " + allocation + " }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(DFG_NORMALIZATION));

        assertEquals(tokens(language, file, false), tokens(language, file, true));
    }

    @Test
    void dfgNormalizationStillRemovesUnusedLiteralArrayInitializers() throws Exception {
        Path file = source("LiteralArray", "static void report() { int[] unused = { 1, 2 }; }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(DFG_NORMALIZATION));

        assertTrue(tokens(language, file, false).contains(VARIABLE_DECL));
        assertFalse(tokens(language, file, true).contains(VARIABLE_DECL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"int unused = next(seed).state;", "int unused = next(seed).child.state;", "next(seed).state = 2;",
            "next(seed).state += 2;"})
    void dfgNormalizationPreservesCallsInFieldReceivers(String statement) throws Exception {
        Path file = source("FieldReceiver", "int state; FieldReceiver child; static FieldReceiver next(int seed) { return null; } "
                + "static void sample() { int seed = 1; " + statement + " }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(DFG_NORMALIZATION));

        assertEquals(tokens(language, file, false), tokens(language, file, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"value = 3;", "value += 3;", "value = 3; System.out.println(value);"})
    void singleUseInliningPreservesVariablesWithWrites(String usage) throws Exception {
        Path file = source("WrittenVariable", "static void sample() { int value = 1; " + usage + " }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(inlineSingleUseVariable));

        assertEquals(tokens(language, file, false), tokens(language, file, true));
    }

    @Test
    void singleUseInliningStillReplacesReadOnlyUsage() throws Exception {
        Path file = source("ReadVariable", "static int sample() { int value = 1; return value; }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(inlineSingleUseVariable));

        assertTrue(tokens(language, file, false).contains(VARIABLE_DECL));
        assertFalse(tokens(language, file, true).contains(VARIABLE_DECL));
    }

    @Test
    void emptyRecordRemovalPreservesPopulatedEnums() throws Exception {
        Path file = directory.resolve("Directions.java");
        Files.writeString(file, "package game; enum Directions { UP, DOWN, LEFT, RIGHT }");
        var language = new JavaCpgLanguage().withNormalizations(List.of(removeEmptyRecord));

        var plain = tokens(language, file, false);
        assertTrue(plain.contains(ENUM_ELEMENT));
        assertEquals(plain, tokens(language, file, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"class Empty {}", "enum Empty {}"})
    void emptyRecordRemovalStillRemovesEmptyDeclarations(String declaration) throws Exception {
        Path file = directory.resolve("Empty.java");
        Files.writeString(file, "package example; " + declaration);
        var language = new JavaCpgLanguage().withNormalizations(List.of(removeEmptyRecord));

        assertEquals(List.of(FILE_END), tokens(language, file, true));
    }

    @Test
    void obligatoryAndUnknownNormalizationsCannotBeSelected() {
        var language = new JavaCpgLanguage();
        assertFalse(language.availableNormalizations().contains(wrapThenStatement));
        assertThrows(IllegalArgumentException.class, () -> language.withNormalizations(List.of(wrapThenStatement)));
        assertThrows(IllegalArgumentException.class, () -> language.withNormalizations(List.of(() -> "unknown")));
    }

    @Test
    void registrationIsOrderedIdempotentAndLocal() {
        var adapter = new CpgAdapter(wrapThenStatement, forStatementToWhileStatement, ifWithNegatedConditionResolution, inlineSingleUseVariable,
                forStatementToWhileStatement);
        assertEquals(List.of(forStatementToWhileStatement, ifWithNegatedConditionResolution),
                adapter.snapshot(true).forPhase(ExecutionPhase.AST_TRANSFORM, new GraphMutationContext()).getTransformations());
        adapter.clearTransformations();
        adapter.addTransformation(wrapThenStatement);
        assertEquals(List.of(wrapThenStatement), adapter.snapshot(true).transformations());
        assertTrue(new CpgAdapter().snapshot(true).transformations().isEmpty());
    }

    @Test
    void snapshotsAndPassConfigurationsAreImmutable() {
        var adapter = new CpgAdapter(wrapThenStatement, forStatementToWhileStatement);
        var snapshot = adapter.snapshot(true);
        adapter.clearTransformations();
        adapter.addTransformation(inlineSingleUseVariable);
        assertEquals(List.of(wrapThenStatement, forStatementToWhileStatement), snapshot.transformations());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.transformations().clear());
        var input = new ArrayList<>(List.of(forStatementToWhileStatement));
        var configuration = new TransformationPassConfiguration(input, new GraphMutationContext());
        input.clear();
        assertEquals(List.of(forStatementToWhileStatement), configuration.getTransformations());
        assertThrows(UnsupportedOperationException.class, () -> configuration.getTransformations().clear());
    }

    @Test
    void configurationMutationsAndSnapshotsCanRunConcurrently() throws Exception {
        var adapter = new CpgAdapter(wrapThenStatement);
        var original = adapter.snapshot(true);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> {
                for (int i = 0; i < 1000; i++) {
                    adapter.addTransformation(forStatementToWhileStatement);
                    adapter.clearTransformations();
                }
            });
            var reader = executor.submit(() -> {
                for (int i = 0; i < 1000; i++) {
                    var selection = adapter.snapshot(true).transformations();
                    assertTrue(selection.equals(List.of(wrapThenStatement))
                            || selection.equals(List.of(wrapThenStatement, forStatementToWhileStatement)));
                }
            });
            writer.get();
            reader.get();
        }
        assertEquals(List.of(wrapThenStatement), original.transformations());
    }

    @Test
    void unnormalizedSnapshotDoesNotOverwriteReorderingPreference() {
        var adapter = new CpgAdapter(wrapThenStatement, forStatementToWhileStatement);
        assertFalse(adapter.snapshot(false).reorderingEnabled());
        assertEquals(List.of(wrapThenStatement), adapter.snapshot(false).transformations());
        assertTrue(adapter.snapshot(true).reorderingEnabled());
        adapter.setReorderingEnabled(false);
        adapter.snapshot(false);
        assertFalse(adapter.snapshot(true).reorderingEnabled());
    }

    @Test
    void standardPresetMustBeSelectedExplicitlyAfterReset() throws Exception {
        Path file = source("Inline", "static int twice(int value) { int doubled=value*2; return doubled; }");
        var language = new JavaCpgLanguage();
        language.resetTransformations();
        var obligatoryOnly = tokens(language, file, true);
        language.addTransformations(language.standardTransformations());
        var standard = tokens(language, file, true);
        assertFalse(obligatoryOnly.equals(standard), "Standard preset must enable local inlining");
        language.resetTransformations();
        assertEquals(obligatoryOnly, tokens(language, file, true));
    }
}
