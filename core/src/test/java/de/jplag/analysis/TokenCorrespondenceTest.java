package de.jplag.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.jplag.Provenance;
import de.jplag.ProvenanceAwareToken;
import de.jplag.SharedTokenType;
import de.jplag.Token;
import de.jplag.TokenType;
import de.jplag.analysis.TokenCorrespondence.Group;
import de.jplag.analysis.TokenCorrespondence.Side;

class TokenCorrespondenceTest {
    private static final TokenType DECLARATION_TYPE = () -> "declaration";
    private static final TokenType CALL_TYPE = () -> "call";
    private static final Path SOURCE = Path.of("fixtures", "Example.java");
    private static final SourceRegion DECLARATION = new SourceRegion(SOURCE, 2, 3, 2, 9);
    private static final SourceRegion CALL = new SourceRegion(SOURCE, 4, 1, 4, 16);
    private static final SourceRegion BLOCK = new SourceRegion(SOURCE, 6, 8, 8, 2);
    private static final SourceRegion OTHER_DECLARATION = new SourceRegion(Path.of("other", "Example.java"), 2, 3, 2, 9);

    @Test
    void crossFileReorderingPreservesOriginalPositionsAndMultilineRegions() {
        Path equivalentPath = SOURCE.resolveSibling(Path.of("nested", "..", SOURCE.getFileName().toString()));
        var left = List.of(token(CALL), token(BLOCK), token(SOURCE, DECLARATION), Token.fileEnd(SOURCE.toFile()), token(OTHER_DECLARATION));
        var right = List.of(token(OTHER_DECLARATION), Token.fileEnd(OTHER_DECLARATION.file().toFile()), token(BLOCK), token(CALL),
                token(equivalentPath, DECLARATION));

        var correspondence = TokenCorrespondence.between(left, right);

        assertEquals(List.of(new Group(CALL, Set.of(0), Set.of(3)), new Group(BLOCK, Set.of(1), Set.of(2)),
                new Group(DECLARATION, Set.of(2), Set.of(4)), new Group(OTHER_DECLARATION, Set.of(4), Set.of(0))), correspondence.groups());
        assertEquals(Set.of(), correspondence.left().unmatchedPositions());
        assertEquals(Set.of(), correspondence.right().unmatchedPositions());
    }

    @Test
    void correspondenceIgnoresTokenTypeProvenanceAndTypeOnlyEquality() {
        var declaration = new TypeOnlyToken(DECLARATION_TYPE, DECLARATION, "original");
        var unrelatedCall = new TypeOnlyToken(DECLARATION_TYPE, CALL, "original");
        var transformedDeclaration = new TypeOnlyToken(CALL_TYPE, DECLARATION, "rewritten");
        var correspondence = TokenCorrespondence.between(List.of(declaration, unrelatedCall), List.of(transformedDeclaration));

        assertEquals(declaration, unrelatedCall);
        assertEquals(List.of(new Group(DECLARATION, Set.of(0), Set.of(0))), correspondence.groups());
        assertEquals(Set.of(1), correspondence.left().unmatchedPositions());
        assertEquals(Set.of(), correspondence.right().unmatchedPositions());
    }

    @Test
    void unequalDuplicateMultiplicityRetainsAllPositionsWithoutPairing() {
        Token declaration = token(DECLARATION);
        Token call = token(CALL);
        var correspondence = TokenCorrespondence.between(List.of(declaration, call, declaration),
                List.of(declaration, declaration, call, declaration));

        assertEquals(List.of(new Group(DECLARATION, Set.of(0, 2), Set.of(0, 1, 3)), new Group(CALL, Set.of(1), Set.of(2))), correspondence.groups());
        assertEquals(Set.of(), correspondence.left().unmatchedPositions());
        assertEquals(Set.of(), correspondence.right().unmatchedPositions());
    }

    @Test
    void overlappingRegionsAreNotTreatedAsExactCorrespondence() {
        var extendedDeclaration = new SourceRegion(SOURCE, DECLARATION.startLine(), DECLARATION.startColumn(), DECLARATION.endLine(),
                DECLARATION.endColumn() + 1);
        var correspondence = TokenCorrespondence.between(List.of(token(DECLARATION)), List.of(token(extendedDeclaration)));

        assertEquals(List.of(), correspondence.groups());
        assertEquals(Set.of(0), correspondence.left().unmatchedPositions());
        assertEquals(Set.of(0), correspondence.right().unmatchedPositions());
    }

    @Test
    void unmatchedPositionsExcludeUnusableLocationsAndFileEnds() {
        Token unknown = new Token(DECLARATION_TYPE, SOURCE.toFile(), Token.NO_VALUE, Token.NO_VALUE, Token.NO_VALUE, Token.NO_VALUE, Token.NO_VALUE);
        Token missingFile = new Token(DECLARATION_TYPE, null, 1, 1, 1, 2, Token.NO_VALUE);
        Token positionedFileEnd = new Token(SharedTokenType.FILE_END, SOURCE.toFile(), 1, 1, 1, 2, Token.NO_VALUE);
        var left = List.of(token(CALL), unknown, positionedFileEnd, token(DECLARATION), token(BLOCK), token(CALL));
        var right = List.of(token(OTHER_DECLARATION), token(DECLARATION), missingFile, Token.fileEnd(null));
        var correspondence = TokenCorrespondence.between(left, right);
        var reverse = TokenCorrespondence.between(right, left);

        assertEquals(List.of(new Group(DECLARATION, Set.of(3), Set.of(1))), correspondence.groups());
        assertEquals(new Side(left, Set.of(0, 4, 5)), correspondence.left());
        assertEquals(new Side(right, Set.of(0)), correspondence.right());
        assertEquals(correspondence.right(), reverse.left());
        assertEquals(correspondence.left(), reverse.right());
    }

    @Test
    void sharedGroupsAreImmutable() {
        var correspondence = TokenCorrespondence.between(List.of(token(DECLARATION)), List.of(token(DECLARATION)));

        assertThrows(UnsupportedOperationException.class, () -> correspondence.groups().clear());
        assertThrows(UnsupportedOperationException.class, () -> correspondence.groups().getFirst().leftPositions().clear());
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
