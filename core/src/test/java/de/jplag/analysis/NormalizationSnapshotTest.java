package de.jplag.analysis;

import static de.jplag.analysis.SubmissionCorrespondenceTest.run;
import static de.jplag.analysis.SubmissionCorrespondenceTest.snapshot;
import static de.jplag.analysis.SubmissionCorrespondenceTest.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

import org.junit.jupiter.api.Test;

import de.jplag.Provenance;
import de.jplag.ProvenanceAwareToken;
import de.jplag.Token;
import de.jplag.TokenType;

class NormalizationSnapshotTest {
    private static final TokenType DECLARATION_TYPE = () -> "declaration";
    private static final TokenType CALL_TYPE = () -> "call";
    private static final Path SOURCE = Path.of("fixtures", "Example.java");
    private static final SourceRegion DECLARATION = new SourceRegion(SOURCE, 2, 3, 2, 9);
    private static final SourceRegion CALL = new SourceRegion(SOURCE, 4, 1, 4, 16);
    private static final SourceRegion OTHER_DECLARATION = new SourceRegion(Path.of("other", "Example.java"), 2, 3, 2, 9);

    @Test
    void keysUseExactNormalizedRegionsAndAllowDuplicates() {
        var declaration = new TypeOnlyToken(DECLARATION_TYPE, DECLARATION, "original");
        var unrelatedCall = new TypeOnlyToken(DECLARATION_TYPE, CALL, "original");
        var equivalentPath = SOURCE.resolveSibling(Path.of("nested", "..", SOURCE.getFileName().toString()));
        var overlappingRegion = new SourceRegion(SOURCE, 2, 3, 2, 10);
        var streams = streams(List.of(declaration, unrelatedCall), List.of(new TypeOnlyToken(CALL_TYPE, DECLARATION, "rewritten"),
                token(equivalentPath, DECLARATION), token(overlappingRegion), token(OTHER_DECLARATION)));
        var original = streams.getFirst().tokens();
        var changed = streams.getLast().tokens();

        assertEquals(declaration, unrelatedCall);
        assertNotNull(original.getFirst().correspondenceKey());
        assertEquals(original.getFirst().correspondenceKey(), changed.getFirst().correspondenceKey());
        assertEquals(original.getFirst().correspondenceKey(), changed.get(1).correspondenceKey());
        assertNotEquals(original.getFirst().correspondenceKey(), original.get(1).correspondenceKey());
        assertNotEquals(original.getFirst().correspondenceKey(), changed.get(2).correspondenceKey());
        assertNotEquals(original.getFirst().correspondenceKey(), changed.get(3).correspondenceKey());
        assertTrue(changed.stream().allMatch(token -> token.correspondenceKey() != null));
    }

    @Test
    void fileEndsAndUnlocatedTokensHaveNoKeys() {
        Token unknown = new Token(DECLARATION_TYPE, SOURCE.toFile(), Token.NO_VALUE, Token.NO_VALUE, Token.NO_VALUE, Token.NO_VALUE, Token.NO_VALUE);
        var streams = streams(List.of(token(DECLARATION), Token.fileEnd(SOURCE.toFile()), unknown), List.of(token(DECLARATION), Token.fileEnd(null)));
        var original = streams.getFirst().tokens();
        var changed = streams.getLast().tokens();

        assertNotNull(original.getFirst().correspondenceKey());
        assertEquals(original.getFirst().correspondenceKey(), changed.getFirst().correspondenceKey());
        assertTrue(original.get(1).fileEnd());
        assertNull(original.get(1).region());
        assertNull(original.get(1).correspondenceKey());
        assertNull(changed.get(1).correspondenceKey());
        assertNull(original.get(2).correspondenceKey());
    }

    @Test
    void tokensAbsentFromTheBaselineStillMatchAcrossLaterRuns() {
        var streams = streams(List.of(token(DECLARATION)), List.of(token(CALL)), List.of(token(CALL), token(DECLARATION)));
        var baseline = streams.getFirst().tokens().getFirst();
        var added = streams.get(1).tokens().getFirst();
        var last = streams.getLast().tokens();

        assertNotNull(added.correspondenceKey());
        assertEquals(added.correspondenceKey(), last.getFirst().correspondenceKey());
        assertEquals(baseline.correspondenceKey(), last.get(1).correspondenceKey());
        assertNotEquals(baseline.correspondenceKey(), added.correspondenceKey());
    }

    @SafeVarargs
    private static List<NormalizationSnapshot.Stream> streams(List<Token>... tokenStreams) {
        var root = SOURCE.getParent();
        List<ConfigurationResult> runs = new ArrayList<>();
        for (List<Token> tokens : tokenStreams) {
            runs.add(run(submission("submission", root, tokens)));
        }
        return snapshot(runs.toArray(ConfigurationResult[]::new)).submissions().get(root.toAbsolutePath().normalize()).streams();
    }

    private static Token token(SourceRegion region) {
        return token(region.file(), region);
    }

    private static Token token(Path file, SourceRegion region) {
        return new Token(DECLARATION_TYPE, file.toFile(), region.startLine(), region.startColumn(), region.endLine(), region.endColumn(),
                Token.NO_VALUE);
    }

    private static final class TypeOnlyToken extends Token implements ProvenanceAwareToken {
        private final Provenance provenance;

        private TypeOnlyToken(TokenType type, SourceRegion region, String transformationName) {
            super(type, region.file().toFile(), region.startLine(), region.startColumn(), region.endLine(), region.endColumn(), Token.NO_VALUE);
            provenance = new Provenance(transformationName);
        }

        @Override
        public SequencedSet<Provenance> provenances() {
            return new LinkedHashSet<>(List.of(provenance));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TypeOnlyToken token && getType().equals(token.getType());
        }

        @Override
        public int hashCode() {
            return getType().hashCode();
        }
    }
}
