package de.jplag.reporting.jsonfactory;

import static de.jplag.reporting.jsonfactory.ReportWriterTestSupport.createMockSubmission;
import static de.jplag.reporting.jsonfactory.ReportWriterTestSupport.createMockToken;
import static de.jplag.reporting.jsonfactory.ReportWriterTestSupport.writeComparisonReport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import de.jplag.Match;
import de.jplag.Provenance;
import de.jplag.ProvenanceAwareToken;
import de.jplag.Submission;
import de.jplag.Token;
import de.jplag.reporting.reportobject.model.ComparisonReport;

class ComparisonReportProvenanceTest {

    @Test
    void testMatchedTransformationsAreEncounterOrderedAndDeduplicatedIndependentlyPerSide() {
        ComparisonReport report = compareMatchedTokens(List.of(token("Z", "A"), token("A", "B"), token("Z")),
                List.of(token("B", "C"), token("B", "Z")));

        assertEquals(List.of("Z", "A", "B"), report.matches().get(0).transformationsInFirst());
        assertEquals(List.of("B", "C", "Z"), report.matches().get(0).transformationsInSecond());
    }

    @Test
    void testPlainAndEmptyProvenanceTokensHaveNoMetadata() {
        ComparisonReport report = compareMatchedTokens(List.of(createMockToken(1, 1, 10), token()), List.of(token(), createMockToken(1, 1, 10)));

        assertEquals(List.of(), report.matches().get(0).transformationsInFirst());
        assertEquals(List.of(), report.matches().get(0).transformationsInSecond());
    }

    private ComparisonReport compareMatchedTokens(List<Token> firstTokens, List<Token> secondTokens) {
        List<Token> firstSubmissionTokens = new ArrayList<>(firstTokens);
        firstSubmissionTokens.addFirst(token("first before"));
        firstSubmissionTokens.addLast(token("first after"));
        List<Token> secondSubmissionTokens = new ArrayList<>(secondTokens);
        secondSubmissionTokens.addFirst(token("second before"));
        secondSubmissionTokens.addLast(token("second after"));
        Submission firstSubmission = createMockSubmission("first", firstSubmissionTokens);
        Submission secondSubmission = createMockSubmission("second", secondSubmissionTokens);
        return writeComparisonReport(firstSubmission, secondSubmission, new Match(1, 1, firstTokens.size(), secondTokens.size()));
    }

    private Token token(String... names) {
        SequencedSet<Provenance> provenances = new LinkedHashSet<>();
        for (String name : names) {
            provenances.add(new Provenance(name));
        }
        Token token = mock(Token.class, Mockito.withSettings().extraInterfaces(ProvenanceAwareToken.class));
        when(token.getStartLine()).thenReturn(1);
        when(token.getStartColumn()).thenReturn(1);
        when(token.getEndLine()).thenReturn(1);
        when(token.getEndColumn()).thenReturn(11);
        when(((ProvenanceAwareToken) token).provenances()).thenReturn(Collections.unmodifiableSequencedSet(provenances));
        return token;
    }
}
