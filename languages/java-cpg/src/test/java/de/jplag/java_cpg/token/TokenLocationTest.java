package de.jplag.java_cpg.token;

import static de.jplag.SharedTokenType.FILE_END;
import static de.jplag.java_cpg.token.CpgTokenType.FIELD_DECL;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_END;
import static de.jplag.java_cpg.transformation.TransformationRepository.forStatementToWhileStatement;
import static de.jplag.java_cpg.transformation.TransformationRepository.moveConstantToOnlyUsingClass;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import de.jplag.Token;
import de.jplag.java_cpg.JavaCpgLanguage;

/**
 * Integration tests for token source locations after normalization.
 */
class TokenLocationTest {
    private static final Path BASE_PATH = Path
            .of("src", "test", "resources", "java", "constantClass", "SubmissionA", "edu", "kit", "kastel", "sdq", "jplag", "example")
            .toAbsolutePath();

    /**
     * Converted loops must point to the original for-loop header and body.
     */
    @ParameterizedTest
    @CsvSource({"WHILE_STATEMENT, 6, 9, 6, 31", "WHILE_BLOCK_START, 6, 56, 6, 57", "WHILE_BLOCK_END, 9, 9, 9, 10"})
    void convertedLoopKeepsOriginalSourceLocations(CpgTokenType type, int startLine, int startColumn, int endLine, int endColumn) throws Exception {
        Path file = Path.of("src", "test", "resources", "java", "for2While", "SubmissionA.java").toAbsolutePath();
        var language = new JavaCpgLanguage();
        language.resetTransformations();
        language.addTransformation(forStatementToWhileStatement);

        Token token = language.parse(Set.of(file.toFile()), true).stream().filter(candidate -> candidate.getType() == type).findFirst().orElseThrow();

        assertEquals(file.toFile(), token.getFile());
        assertEquals(startLine, token.getStartLine());
        assertEquals(startColumn, token.getStartColumn());
        assertEquals(endLine, token.getEndLine());
        assertEquals(endColumn, token.getEndColumn());
    }

    /**
     * A field moved to UsingClass must keep its location in DefiningClass without changing UsingClass's token locations.
     */
    @Test
    void movedConstantDoesNotDistortDestinationClassLocation() throws Exception {
        Path constants = BASE_PATH.resolve("defining/DefiningClass.java");
        Path usage = BASE_PATH.resolve("using/UsingClass.java");
        var language = new JavaCpgLanguage();
        language.resetTransformations();
        language.addTransformation(moveConstantToOnlyUsingClass);

        var tokens = language.parse(Set.of(constants.toFile(), usage.toFile()), true);
        Token classEnd = tokens.stream().filter(token -> token.getType() == RECORD_DECL_END && token.getFile().equals(usage.toFile())).findFirst()
                .orElseThrow();

        assertEquals(14, classEnd.getStartLine());
        assertEquals(6, classEnd.getStartColumn());
        assertEquals(17, classEnd.getEndLine());
        assertEquals(2, classEnd.getEndColumn());

        Token movedField = tokens.stream().dropWhile(token -> token.getType() != RECORD_DECL_BEGIN || !token.getFile().equals(usage.toFile()))
                .takeWhile(token -> token.getType() != FILE_END).filter(token -> token.getType() == FIELD_DECL).findFirst().orElseThrow();
        assertEquals(constants.toFile(), movedField.getFile());
        assertEquals(8, movedField.getStartLine());
        assertEquals(5, movedField.getStartColumn());
        assertEquals(8, movedField.getEndLine());
        assertEquals(45, movedField.getEndColumn());
    }
}
