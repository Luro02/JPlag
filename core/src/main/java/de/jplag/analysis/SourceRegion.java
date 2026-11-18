package de.jplag.analysis;

import java.nio.file.Path;
import java.util.Optional;

import de.jplag.Token;

/**
 * A nonempty region in a source file, with one-based coordinates and an exclusive end.
 * @param file the absolute normalized source path
 * @param startLine the line of the first character
 * @param startColumn the column of the first character
 * @param endLine the line of the exclusive end position
 * @param endColumn the column of the exclusive end position
 */
public record SourceRegion(Path file, int startLine, int startColumn, int endLine, int endColumn) {
    public SourceRegion {
        if (isInvalid(startLine, startColumn, endLine, endColumn)) {
            throw new IllegalArgumentException("Invalid source region: %d:%d-%d:%d".formatted(startLine, startColumn, endLine, endColumn));
        }
        file = file.toAbsolutePath().normalize();
    }

    /**
     * Constructs a source region from a token's location.
     * @param token the token
     * @return a source region or empty if the token does not have a valid location
     */
    public static Optional<SourceRegion> from(Token token) {
        int startLine = token.getStartLine();
        int startColumn = token.getStartColumn();
        int endLine = token.getEndLine();
        int endColumn = token.getEndColumn();

        if (token.getFile() == null || isInvalid(startLine, startColumn, endLine, endColumn)) {
            return Optional.empty();
        }

        return Optional.of(new SourceRegion(token.getFile().toPath(), startLine, startColumn, endLine, endColumn));
    }

    private static boolean isInvalid(int startLine, int startColumn, int endLine, int endColumn) {
        return startLine <= 0 || startColumn <= 0 || endLine <= 0 || endColumn <= 0 || endLine < startLine
                || (endLine == startLine && endColumn <= startColumn);
    }
}
