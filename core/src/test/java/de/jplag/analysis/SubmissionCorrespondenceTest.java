package de.jplag.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.jplag.JPlagResult;
import de.jplag.Normalization;
import de.jplag.Submission;
import de.jplag.SubmissionSet;
import de.jplag.Token;
import de.jplag.TokenType;
import de.jplag.java.JavaLanguage;
import de.jplag.options.JPlagOptions;

class SubmissionCorrespondenceTest {
    private static final TokenType STATEMENT = () -> "statement";
    private static final Path FIRST_ROOT = Path.of("fixtures", "first", "submission").toAbsolutePath().normalize();
    private static final Path SECOND_ROOT = Path.of("fixtures", "second", "submission").toAbsolutePath().normalize();
    private static final Path THIRD_ROOT = Path.of("fixtures", "third", "submission").toAbsolutePath().normalize();

    @Test
    void streamsAreGroupedByNormalizedSourceRootRatherThanNameOrResultOrder() {
        var firstSubmission = submission("same", FIRST_ROOT);
        var secondSubmission = submission("same", SECOND_ROOT);
        var renamedSubmission = submission("renamed", FIRST_ROOT.resolve("nested").resolve(".."));
        renamedSubmission.setTokenList(renamedSubmission.getTokenList().reversed());
        var first = run(firstSubmission, secondSubmission);
        var second = run(submission("same", SECOND_ROOT), renamedSubmission);

        var snapshot = snapshot(first, second);

        assertEquals(List.of(FIRST_ROOT, SECOND_ROOT), List.copyOf(snapshot.submissions().keySet()));
        var firstStreams = snapshot.submissions().get(FIRST_ROOT).streams();
        assertEquals(snapshot.configurations().stream().map(NormalizationSnapshot.Configuration::id).toList(),
                firstStreams.stream().map(NormalizationSnapshot.Stream::configurationId).toList());
        assertNotNull(firstStreams.getFirst().tokens().getFirst().correspondenceKey());
        assertEquals(firstStreams.getFirst().tokens().getFirst().correspondenceKey(), firstStreams.getLast().tokens().get(1).correspondenceKey());
        var secondStreams = snapshot.submissions().get(SECOND_ROOT).streams();
        assertEquals(secondStreams.getFirst().tokens().getFirst().correspondenceKey(),
                secondStreams.getLast().tokens().getFirst().correspondenceKey());
    }

    @Test
    void missingStreamsDoNotRemoveSubmissionsOrPreventMatchingLaterRuns() {
        var first = run(submission("first", FIRST_ROOT), submission("second", SECOND_ROOT));
        var middle = run(submission("first", FIRST_ROOT), submission("third", THIRD_ROOT));
        var last = run(submission("third", THIRD_ROOT), submission("second", SECOND_ROOT));

        var snapshot = snapshot(first, middle, last);
        var configurations = snapshot.configurations();
        var firstStreams = snapshot.submissions().get(FIRST_ROOT).streams();
        var secondStreams = snapshot.submissions().get(SECOND_ROOT).streams();
        var thirdStreams = snapshot.submissions().get(THIRD_ROOT).streams();

        assertEquals(List.of(FIRST_ROOT, SECOND_ROOT, THIRD_ROOT), List.copyOf(snapshot.submissions().keySet()));
        assertEquals(List.of(configurations.getFirst().id(), configurations.get(1).id()),
                firstStreams.stream().map(NormalizationSnapshot.Stream::configurationId).toList());
        assertEquals(List.of(configurations.getFirst().id(), configurations.getLast().id()),
                secondStreams.stream().map(NormalizationSnapshot.Stream::configurationId).toList());
        assertEquals(List.of(configurations.get(1).id(), configurations.getLast().id()),
                thirdStreams.stream().map(NormalizationSnapshot.Stream::configurationId).toList());
        assertNotNull(thirdStreams.getFirst().tokens().getFirst().correspondenceKey());
        assertEquals(thirdStreams.getFirst().tokens().getFirst().correspondenceKey(), thirdStreams.getLast().tokens().getFirst().correspondenceKey());
    }

    @Test
    void capturedStreamKeepsOriginalOrderAfterSubmissionTokensAreReplaced() {
        var submission = submission("first", FIRST_ROOT);
        var originalTokens = submission.getTokenList();
        var originalRun = run(submission);

        submission.setTokenList(originalTokens.reversed());
        var updatedRun = run(submission);
        var streams = snapshot(originalRun, updatedRun).submissions().get(FIRST_ROOT).streams();
        var original = streams.getFirst().tokens();
        var updated = streams.getLast().tokens();

        assertEquals(originalTokens.stream().map(token -> SourceRegion.from(token).orElseThrow()).toList(),
                original.stream().map(NormalizationSnapshot.TokenData::region).toList());
        assertNotNull(original.getFirst().correspondenceKey());
        assertEquals(original.getFirst().correspondenceKey(), updated.get(1).correspondenceKey());
        assertEquals(original.get(1).correspondenceKey(), updated.getFirst().correspondenceKey());
    }

    @Test
    void runSnapshotsTheOrderedNormalizationSelection() {
        List<Normalization> selection = List.of(() -> "first", () -> "second");
        var mutableSelection = new ArrayList<>(selection);
        var run = new ConfigurationResult(mutableSelection, result());

        mutableSelection.clear();

        assertEquals(selection, run.normalizations());
        assertThrows(UnsupportedOperationException.class, () -> run.normalizations().clear());
    }

    static NormalizationSnapshot snapshot(ConfigurationResult... runs) {
        return NormalizationSnapshot.from(new NormalizationAnalysis.Result(List.of(runs), runs[runs.length - 1]));
    }

    static ConfigurationResult run(Submission... validSubmissions) {
        return new ConfigurationResult(List.of(), result(validSubmissions));
    }

    private static JPlagResult result(Submission... validSubmissions) {
        var submissions = mock(SubmissionSet.class);
        when(submissions.getSubmissions()).thenReturn(List.of(validSubmissions));
        var options = new JPlagOptions(new JavaLanguage(), Set.of(), Set.of());
        return new JPlagResult(List.of(), submissions, 0, options);
    }

    private static Submission submission(String name, Path root) {
        var file = root.resolve("Main.java").toFile();
        return submission(name, root,
                List.of(new Token(STATEMENT, file, 1, 1, 1, 5, Token.NO_VALUE), new Token(STATEMENT, file, 2, 1, 2, 5, Token.NO_VALUE)));
    }

    static Submission submission(String name, Path root, List<Token> tokens) {
        var file = root.resolve("Main.java").toFile();
        var submission = new Submission(name, root.toFile(), true, List.of(file), new JavaLanguage());
        submission.setTokenList(tokens);
        return submission;
    }
}
