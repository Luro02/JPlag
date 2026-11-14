package de.jplag.reporting.jsonfactory;

import static de.jplag.reporting.jsonfactory.ReportWriterTestSupport.createMockSubmission;
import static de.jplag.reporting.jsonfactory.ReportWriterTestSupport.createMockToken;
import static de.jplag.reporting.jsonfactory.ReportWriterTestSupport.writeBaseCodeReport;
import static de.jplag.reporting.jsonfactory.ReportWriterTestSupport.writeComparisonReport;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.jplag.Match;
import de.jplag.Submission;
import de.jplag.Token;
import de.jplag.reporting.reportobject.model.BaseCodeMatch;
import de.jplag.reporting.reportobject.model.CodePosition;
import de.jplag.reporting.reportobject.model.ComparisonReport;

class ReportTokenPositionTestTest {

    @Test
    void testCorrectTokenPositionsInComparisonReport() {
        Submission firstSubmission = createMockSubmission("first", createPositionTokens());
        Submission secondSubmission = createMockSubmission("second", createPositionTokens());

        ComparisonReport comparisonReport = writeComparisonReport(firstSubmission, secondSubmission, new Match(0, 0, 3, 2));

        assertEquals(1, comparisonReport.matches().size());
        var match = comparisonReport.matches().get(0);
        assertEquals(new CodePosition(1, 0, 0), match.startInFirst());
        assertEquals(new CodePosition(2, 10, 2), match.endInFirst());
        assertEquals(new CodePosition(1, 0, 0), match.startInSecond());
        assertEquals(new CodePosition(2, 10, 1), match.endInSecond());
        assertEquals(3, match.lengthOfFirst());
        assertEquals(2, match.lengthOfSecond());
        assertEquals("file.java", match.firstFileName());
        assertEquals("file.java", match.secondFileName());
    }

    @Test
    void testPositionsUseSourceOrderWithinMatchedTokenRange() {
        Submission firstSubmission = createMockSubmission("first", List.of(createMockToken(1, 1, 1), createMockToken(3, 8, 2),
                createMockToken(1, 9, 1), createMockToken(1, 2, 2), createMockToken(3, 2, 3), createMockToken(99, 1, 1)));
        Submission secondSubmission = createMockSubmission("second", List.of(createMockToken(1, 1, 1), createMockToken(2, 1, 1),
                createMockToken(6, 8, 4), createMockToken(4, 9, 1), createMockToken(4, 2, 2), createMockToken(99, 1, 1)));

        ComparisonReport comparisonReport = writeComparisonReport(firstSubmission, secondSubmission, new Match(1, 2, 4, 3));

        var match = comparisonReport.matches().get(0);
        assertEquals(new CodePosition(1, 1, 1), match.startInFirst());
        assertEquals(new CodePosition(3, 9, 4), match.endInFirst());
        assertEquals(new CodePosition(4, 1, 2), match.startInSecond());
        assertEquals(new CodePosition(6, 11, 4), match.endInSecond());
        assertEquals(4, match.lengthOfFirst());
        assertEquals(3, match.lengthOfSecond());
    }

    @Test
    void testCorrectTokenPositionsInBasecodeReport() {
        Submission submission = createMockSubmission("first", createPositionTokens());
        Submission baseCodeSubmission = createMockSubmission("basecode", createPositionTokens());

        List<BaseCodeMatch> baseCodeMatches = writeBaseCodeReport(submission, baseCodeSubmission, new Match(0, 0, 3, 2));

        assertEquals(1, baseCodeMatches.size());
        BaseCodeMatch match = baseCodeMatches.get(0);
        assertEquals(new CodePosition(1, 0, 0), match.start());
        assertEquals(new CodePosition(2, 10, 2), match.end());
        assertEquals(3, match.tokens());
        assertEquals("file.java", match.fileName());
    }

    private List<Token> createPositionTokens() {
        return List.of(createMockToken(1, 1, 10), createMockToken(2, 1, 10), createMockToken(2, 3, 2), createMockToken(2, 10, 2));
    }
}
