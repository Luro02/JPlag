package de.jplag.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void capturedStreamKeepsOriginalOrderAfterSubmissionTokensAreReplaced() {
        var submission = submission("first", FIRST_ROOT);
        var originalTokens = submission.getTokenList();
        var originalRun = run(submission);

        submission.setTokenList(originalTokens.reversed());
        var updatedRun = run(submission);
        assertEquals(originalTokens, originalRun.tokensBySubmission().get(FIRST_ROOT));
        assertEquals(originalTokens.reversed(), updatedRun.tokensBySubmission().get(FIRST_ROOT));
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
