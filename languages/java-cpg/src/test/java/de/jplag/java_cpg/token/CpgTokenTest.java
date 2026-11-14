package de.jplag.java_cpg.token;

import static de.jplag.java_cpg.token.CpgTokenType.ASSIGNMENT;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import de.fraunhofer.aisec.cpg.graph.statements.expressions.AssignExpression;
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation;
import de.fraunhofer.aisec.cpg.sarif.Region;

class CpgTokenTest {
    @TempDir
    private Path directory;

    @Test
    void missingSourceLocationIsRejected() {
        assertThrows(IllegalStateException.class, () -> CpgToken.fromNode(ASSIGNMENT, new AssignExpression(), false));
    }

    @ParameterizedTest(name = "Rejects {0} source regions")
    @CsvSource({"unknown, -1, -1, -1, -1", "empty, 1, 1, 1, 1", "backwards, 2, 1, 1, 2"})
    void invalidSourceRegionIsRejected(String description, int startLine, int startColumn, int endLine, int endColumn) {
        var assignment = assignment(new Region(startLine, startColumn, endLine, endColumn));

        assertThrows(IllegalStateException.class, () -> CpgToken.fromNode(ASSIGNMENT, assignment, false));
    }

    private AssignExpression assignment(Region region) {
        var assignment = new AssignExpression();
        assignment.setLocation(new PhysicalLocation(directory.resolve("Sample.java").toUri(), region));
        return assignment;
    }
}
