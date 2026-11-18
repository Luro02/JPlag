package de.jplag.reporting.reportobject.mapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import de.jplag.JPlagComparison;
import de.jplag.JPlagResult;
import de.jplag.Submission;
import de.jplag.TestBase;
import de.jplag.exceptions.ExitException;
import de.jplag.options.JPlagOptions;
import de.jplag.reporting.jsonfactory.ComparisonReportWriter;
import de.jplag.reporting.reportobject.model.ComparisonReport;
import de.jplag.reporting.reportobject.writer.DummyResultWriter;
import de.jplag.reporting.reportobject.writer.JPlagResultWriter;

class ComparisonReportWriterTest extends TestBase {
    private final JPlagResultWriter fileWriter = new DummyResultWriter();

    @Test
    void firsLevelOfLookupMapComplete() throws ExitException {
        JPlagResult result = runJPlagWithDefaultOptions("PartialPlagiarism");
        var mapper = new ComparisonReportWriter(Submission::getName, fileWriter);

        Map<String, Map<String, String>> stringMapMap = mapper.writeComparisonReports(result);

        firstLevelOfMapContains(stringMapMap, "A", "B", "C", "D", "E");
    }

    @Test
    void secondLevelOfLookupMapComplete() throws ExitException {
        JPlagResult result = runJPlagWithDefaultOptions("PartialPlagiarism");
        var mapper = new ComparisonReportWriter(Submission::getName, fileWriter);

        Map<String, Map<String, String>> stringMapMap = mapper.writeComparisonReports(result);

        secondLevelOfMapContains(stringMapMap, "A", "B", "C", "D", "E");
        secondLevelOfMapContains(stringMapMap, "B", "A", "C", "D", "E");
        secondLevelOfMapContains(stringMapMap, "C", "B", "A", "D", "E");
        secondLevelOfMapContains(stringMapMap, "D", "B", "C", "A", "E");
        secondLevelOfMapContains(stringMapMap, "E", "B", "C", "D", "A");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void collidingNamesDoNotOverwriteComparisonReports(boolean reverseOrder) {
        var comparisons = List.of(comparison("a", "b-c"), comparison("a-b", "c"), comparison("a", "b-c-2"));
        var options = mock(JPlagOptions.class);
        when(options.maximumNumberOfComparisons()).thenReturn(comparisons.size());
        var result = mock(JPlagResult.class);
        when(result.getOptions()).thenReturn(options);
        when(result.getComparisons(comparisons.size())).thenReturn(reverseOrder ? comparisons.reversed() : comparisons);
        var writer = mock(JPlagResultWriter.class);

        var lookup = new ComparisonReportWriter(Submission::getName, writer).writeComparisonReports(result);

        var reports = ArgumentCaptor.forClass(ComparisonReport.class);
        var paths = ArgumentCaptor.forClass(Path.class);
        verify(writer, times(comparisons.size())).addJsonEntry(reports.capture(), paths.capture());
        Assertions.assertEquals(comparisons.size(), Set.copyOf(paths.getAllValues()).size());
        for (int i = 0; i < comparisons.size(); i++) {
            var report = reports.getAllValues().get(i);
            String left = report.firstSubmissionId();
            String right = report.secondSubmissionId();
            String fileName = lookup.get(left).get(right);
            Assertions.assertEquals(fileName, lookup.get(right).get(left));
            Assertions.assertEquals(Path.of(ComparisonReportWriter.BASEPATH, fileName), paths.getAllValues().get(i));
        }
    }

    private JPlagComparison comparison(String left, String right) {
        var leftSubmission = mock(Submission.class);
        when(leftSubmission.getName()).thenReturn(left);
        var rightSubmission = mock(Submission.class);
        when(rightSubmission.getName()).thenReturn(right);
        var comparison = mock(JPlagComparison.class);
        when(comparison.firstSubmission()).thenReturn(leftSubmission);
        when(comparison.secondSubmission()).thenReturn(rightSubmission);
        when(comparison.matches()).thenReturn(List.of());
        return comparison;
    }

    private void secondLevelOfMapContains(Map<String, Map<String, String>> stringMapMap, String firstLevelSubmission,
            String... secondLevelSubmissions) {
        for (String secondLevelSubmission : secondLevelSubmissions) {
            Assertions.assertNotNull(stringMapMap.get(firstLevelSubmission).get(secondLevelSubmission));
            Assertions.assertFalse(stringMapMap.get(firstLevelSubmission).get(secondLevelSubmission).isEmpty());

        }

    }

    private void firstLevelOfMapContains(Map<String, Map<String, String>> stringMapMap, String... names) {
        for (String name : names) {
            Assertions.assertNotNull(stringMapMap.get(name));
        }
    }
}
