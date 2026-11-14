package de.jplag.reporting.jsonfactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.mockito.MockedStatic;
import org.mockito.Mockito;

import de.jplag.JPlagComparison;
import de.jplag.JPlagResult;
import de.jplag.Match;
import de.jplag.Submission;
import de.jplag.Token;
import de.jplag.options.JPlagOptions;
import de.jplag.reporting.FilePathUtil;
import de.jplag.reporting.reportobject.model.BaseCodeMatch;
import de.jplag.reporting.reportobject.model.ComparisonReport;

final class ReportWriterTestSupport {

    private ReportWriterTestSupport() {
    }

    static Submission createMockSubmission(String name, List<Token> tokens) {
        Submission submission = mock(Submission.class);
        when(submission.getName()).thenReturn(name);
        when(submission.getTokenList()).thenReturn(tokens);
        return submission;
    }

    static Token createMockToken(int line, int column, int length) {
        Token token = mock(Token.class);
        when(token.getStartLine()).thenReturn(line);
        when(token.getStartColumn()).thenReturn(column);
        when(token.getEndLine()).thenReturn(line);
        when(token.getEndColumn()).thenReturn(column + length);
        return token;
    }

    static ComparisonReport writeComparisonReport(Submission firstSubmission, Submission secondSubmission, Match... matches) {
        JPlagComparison comparison = createMockComparison(firstSubmission, secondSubmission, matches);
        JPlagResult result = createMockResult(comparison);
        TestableReportWriter resultWriter = new TestableReportWriter();
        Map<String, Map<String, String>> comparisonReportOutput;
        try (MockedStatic<FilePathUtil> mockedFilePathUtil = Mockito.mockStatic(FilePathUtil.class)) {
            mockedFilePathUtil.when(() -> FilePathUtil.getRelativeSubmissionPath(any(), any(), any())).thenReturn(Path.of("file.java"));
            comparisonReportOutput = new ComparisonReportWriter(Submission::getName, resultWriter).writeComparisonReports(result);
        }
        String fileName = comparisonReportOutput.get(firstSubmission.getName()).get(secondSubmission.getName());
        return (ComparisonReport) resultWriter.getJsonEntry(Path.of(ComparisonReportWriter.BASEPATH, fileName));
    }

    @SuppressWarnings("unchecked")
    static List<BaseCodeMatch> writeBaseCodeReport(Submission submission, Submission baseCodeSubmission, Match... matches) {
        JPlagComparison comparison = createMockComparison(submission, baseCodeSubmission, matches);
        when(submission.getBaseCodeComparison()).thenReturn(comparison);
        JPlagResult result = createMockResult(comparison);
        TestableReportWriter resultWriter = new TestableReportWriter();
        try (MockedStatic<FilePathUtil> mockedFilePathUtil = Mockito.mockStatic(FilePathUtil.class)) {
            mockedFilePathUtil.when(() -> FilePathUtil.getRelativeSubmissionPath(any(), any(), any())).thenReturn(Path.of("file.java"));
            new BaseCodeReportWriter(Submission::getName, resultWriter).writeBaseCodeReport(result);
        }
        return (List<BaseCodeMatch>) resultWriter.getJsonEntry(Path.of("basecode", submission.getName() + ".json"));
    }

    private static JPlagComparison createMockComparison(Submission firstSubmission, Submission secondSubmission, Match... matches) {
        JPlagComparison comparison = mock(JPlagComparison.class);
        when(comparison.firstSubmission()).thenReturn(firstSubmission);
        when(comparison.secondSubmission()).thenReturn(secondSubmission);
        when(comparison.matches()).thenReturn(List.of(matches));
        return comparison;
    }

    private static JPlagResult createMockResult(JPlagComparison comparison) {
        JPlagOptions options = mock(JPlagOptions.class);
        when(options.maximumNumberOfComparisons()).thenReturn(1);
        JPlagResult result = mock(JPlagResult.class);
        when(result.getOptions()).thenReturn(options);
        when(result.getComparisons(1)).thenReturn(List.of(comparison));
        return result;
    }
}
