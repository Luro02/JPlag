package de.jplag;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import de.jplag.analysis.NormalizationAnalysisOptions;
import de.jplag.exceptions.ExitException;
import de.jplag.java.JavaLanguage;
import de.jplag.options.JPlagOptions;

class NormalizationTest extends TestBase {
    private final Map<String, List<TokenType>> tokenStringMap;
    private final List<TokenType> originalTokenString;

    NormalizationTest() throws ExitException {
        JPlagOptions options = getDefaultOptions("normalization");
        SubmissionSetBuilder builder = new SubmissionSetBuilder(options);
        SubmissionSet submissionSet = builder.buildSubmissionSet();
        submissionSet.normalizeSubmissions();
        Function<Submission, List<TokenType>> getTokenString = submission -> submission.getTokenList().stream().map(Token::getType).toList();
        tokenStringMap = submissionSet.getSubmissions().stream().collect(Collectors.toMap(Submission::getName, getTokenString));
        originalTokenString = tokenStringMap.get("Squares.java");
    }

    @Test
    void normalizationAnalysisRunsBothConfigurationsAndReturnsEnabledResult() throws ExitException, ParsingException {
        var language = mock(NormalizableLanguage.class, delegatesTo(new JavaLanguage()));
        var disabledLanguage = mock(NormalizableLanguage.class, delegatesTo(new JavaLanguage()));
        var enabledLanguage = mock(NormalizableLanguage.class, delegatesTo(new JavaLanguage()));
        List<Normalization> normalizations = List.of(() -> "normalization");
        doReturn(normalizations).when(language).availableNormalizations();
        doReturn(disabledLanguage).when(language).withNormalizations(List.of());
        doReturn(enabledLanguage).when(language).withNormalizations(normalizations);
        var options = getDefaultOptions("normalization").with().language(language)
                .normalizationAnalysisOptions(new NormalizationAnalysisOptions().withEnabled(true)).build();

        var result = JPlag.run(options);

        verify(disabledLanguage, atLeastOnce()).parse(anySet(), eq(false));
        verify(enabledLanguage, atLeastOnce()).parse(anySet(), eq(true));
        Assertions.assertSame(enabledLanguage, result.getOptions().language());
        Assertions.assertTrue(result.getOptions().normalize());
        Assertions.assertFalse(result.getAllComparisons().isEmpty());
        Assertions.assertSame(language, options.language());
        Assertions.assertFalse(options.normalize());
    }

    @Test
    void testInsertionNormalization() {
        Assertions.assertIterableEquals(originalTokenString, tokenStringMap.get("SquaresInserted.java"));
    }

    @Test
    void testReorderingNormalization() {
        Assertions.assertIterableEquals(originalTokenString, tokenStringMap.get("SquaresReordered.java"));
    }

    @Test
    void testInsertionReorderingNormalization() {
        Assertions.assertIterableEquals(originalTokenString, tokenStringMap.get("SquaresInsertedReordered.java"));
    }
}