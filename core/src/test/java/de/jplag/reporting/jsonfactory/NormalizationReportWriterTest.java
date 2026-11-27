package de.jplag.reporting.jsonfactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jplag.IdentityAwareToken;
import de.jplag.JPlag;
import de.jplag.JPlagComparison;
import de.jplag.JPlagResult;
import de.jplag.NormalizableLanguage;
import de.jplag.Normalization;
import de.jplag.Provenance;
import de.jplag.ProvenanceAwareToken;
import de.jplag.Submission;
import de.jplag.SubmissionSet;
import de.jplag.Token;
import de.jplag.TokenType;
import de.jplag.analysis.ConfigurationResult;
import de.jplag.analysis.NormalizationAnalysis;
import de.jplag.analysis.NormalizationSnapshot;
import de.jplag.java.JavaLanguage;
import de.jplag.options.JPlagOptions;
import de.jplag.reporting.reportobject.ReportObjectFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class NormalizationReportWriterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenType STATEMENT = () -> "statement description";

    /**
     * Temporary root for source submissions and report archives.
     */
    @TempDir
    Path directory;

    @Test
    void ordinaryReportHasNoInspectorEntries() throws Exception {
        var first = submission("first");
        var second = submission("second");
        var result = result(false, first, second);
        var writer = export(result);

        assertFalse(writer.jsonEntries.keySet().stream().anyMatch(path -> path.toString().startsWith("normalization")));
    }

    @Test
    void roundTripsInspectorDataAndSourcePathsThroughZipWriter() throws Exception {
        var first = submission("first");
        var second = submission("second");
        var baseline = new ConfigurationResult(List.of(), result(false, first, second));
        var reporting = result(true, first, second);
        var normalized = new ConfigurationResult(List.of(() -> "selected"), reporting);
        var snapshot = NormalizationSnapshot.from(new NormalizationAnalysis.Result(List.of(baseline, normalized), normalized));
        reporting.setNormalizationAnalysis(snapshot);
        Path archive = directory.resolve("normalization.jplag");

        new ReportObjectFactory(archive.toFile()).createAndSaveReport(reporting);

        try (var zip = new ZipFile(archive.toFile())) {
            JsonNode metadata = MAPPER.readTree(zip.getInputStream(zip.getEntry("normalizationAnalysis.json")));
            assertEquals(MAPPER.valueToTree(snapshot.configurations()), metadata.get("configurations"));
            assertEquals(snapshot.baselineConfigurationId(), metadata.get("baselineConfigurationId").asText());
            assertEquals(Set.of("first", "second"), fields(metadata.get("submissions")));
            for (String submissionId : List.of("first", "second")) {
                String payloadPath = metadata.get("submissions").get(submissionId).asText();
                assertEquals("normalization/" + submissionId + ".json", payloadPath);
                JsonNode payload = MAPPER.readTree(zip.getInputStream(zip.getEntry(payloadPath)));
                assertEquals(Set.of("streams"), fields(payload));
                assertEquals(2, payload.get("streams").size());
                for (int index = 0; index < snapshot.configurations().size(); index++) {
                    JsonNode stream = payload.get("streams").get(index);
                    assertEquals(snapshot.configurations().get(index).id(), stream.get("configurationId").asText());
                    assertEquals(2, stream.get("tokens").size());
                    String sourcePath = stream.get("tokens").get(0).get("region").get("file").asText();
                    assertEquals(submissionId + "/nested/Main.java", sourcePath);
                    var sourceEntry = zip.getEntry("files/" + sourcePath);
                    assertNotNull(sourceEntry);
                    try (var source = zip.getInputStream(sourceEntry)) {
                        assertEquals(Files.readString(directory.resolve(sourcePath)),
                                new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                JsonNode baselineKey = payload.get("streams").get(0).get("tokens").get(0).get("correspondenceKey");
                assertTrue(baselineKey.isIntegralNumber());
                assertEquals(baselineKey, payload.get("streams").get(1).get("tokens").get(0).get("correspondenceKey"));
            }
        }
    }

    @Test
    void exportsExactSchemaWithIndicesProvenanceExclusiveRegionsAndLegacyKeys() throws Exception {
        var first = spy(submission("first"));
        var second = submission("second");
        var excluded = submission("excluded");
        File file = first.getFiles().iterator().next();
        doReturn(Map.of(file, 5)).when(first).getTokenCountPerFile();
        File unavailable = directory.resolve("not-archived.java").toFile();
        List<Token> baselineTokens = List.of(token(file, 1), Token.fileEnd(file), token(file, 1), token(file, 4), token(unavailable, 1),
                token(unavailable, 4), new Token(STATEMENT, file, -1, -1, -1, -1, -1), Token.fileEnd(file));
        first.setTokenList(baselineTokens);
        var baseline = new ConfigurationResult(List.of(), result(false, first, second, excluded));
        first.setTokenList(List.of(provenanceToken(file), token(file, 1), token(file, 1), token(file, 8), token(unavailable, 1),
                token(unavailable, 8), Token.fileEnd(file)));
        var reporting = result(true, first, second, excluded);
        var fullyNormalized = new ConfigurationResult(List.of(() -> "Z", () -> "A"), reporting);
        reporting.setNormalizationAnalysis(
                NormalizationSnapshot.from(new NormalizationAnalysis.Result(List.of(baseline, fullyNormalized), fullyNormalized)));
        var writer = export(reporting);
        JsonNode metadata = json(writer, "normalizationAnalysis.json");

        assertEquals(Set.of("configurations", "baselineConfigurationId", "submissions"), fields(metadata));
        assertEquals(2, metadata.get("configurations").size());
        var baselineConfiguration = metadata.get("configurations").get(0);
        var reportingConfiguration = metadata.get("configurations").get(1);
        assertEquals(Set.of("id", "name", "normalizations", "normalize"), fields(reportingConfiguration));
        assertEquals("Baseline", baselineConfiguration.get("name").asText());
        assertEquals("Fully normalized", reportingConfiguration.get("name").asText());
        assertEquals(List.of("Z", "A"), MAPPER.convertValue(reportingConfiguration.get("normalizations"), List.class));
        assertFalse(baselineConfiguration.get("normalize").asBoolean());
        assertTrue(reportingConfiguration.get("normalize").asBoolean());
        assertEquals(baselineConfiguration.get("id"), metadata.get("baselineConfigurationId"));
        assertEquals(Set.of("first", "second"), fields(metadata.get("submissions")));
        assertEquals("normalization/first.json", metadata.get("submissions").get("first").asText());
        assertFalse(writer.jsonEntries.containsKey(Path.of("normalization", "excluded.json")));

        JsonNode data = json(writer, "normalization/first.json");
        assertEquals(Set.of("streams"), fields(data));
        JsonNode baselineStream = data.get("streams").get(0);
        JsonNode reportingStream = data.get("streams").get(1);
        assertEquals(Set.of("configurationId", "tokens"), fields(baselineStream));
        assertEquals(baselineConfiguration.get("id"), baselineStream.get("configurationId"));
        assertEquals(reportingConfiguration.get("id"), reportingStream.get("configurationId"));
        JsonNode tokens = baselineStream.get("tokens");
        assertEquals(baselineTokens.size(), tokens.size());
        assertEquals(7, reportingStream.get("tokens").size());
        for (JsonNode stream : data.get("streams")) {
            for (int index = 0; index < stream.get("tokens").size(); index++) {
                JsonNode token = stream.get("tokens").get(index);
                assertEquals(index, token.get("index").asInt());
                assertEquals(Set.of("index", "type", "region", "transformations", "fileEnd", "correspondenceKey"), fields(token));
                if (token.get("fileEnd").asBoolean()) {
                    assertTrue(token.get("region").isNull());
                    assertTrue(token.get("correspondenceKey").isNull());
                }
            }
        }
        assertEquals("statement description", tokens.get(0).get("type").asText());
        assertFalse(tokens.get(0).get("fileEnd").asBoolean());
        assertTrue(tokens.get(0).get("transformations").isEmpty());
        assertEquals("FILE_END", tokens.get(1).get("type").asText());
        assertTrue(tokens.get(1).get("fileEnd").asBoolean());
        assertTrue(tokens.get(1).get("region").isNull());
        assertEquals(List.of("Z", "A"), MAPPER.convertValue(reportingStream.get("tokens").get(0).get("transformations"), List.class));
        JsonNode region = tokens.get(0).get("region");
        assertEquals(Set.of("file", "startLine", "startColumn", "endLine", "endColumn"), fields(region));
        assertEquals("first/nested/Main.java", region.get("file").asText());
        assertEquals(1, region.get("startLine").asInt());
        assertEquals(2, region.get("startColumn").asInt());
        assertEquals(2, region.get("endLine").asInt());
        assertEquals(7, region.get("endColumn").asInt());
        assertTrue(tokens.get(4).get("region").isNull());
        assertTrue(tokens.get(5).get("region").isNull());
        assertTrue(tokens.get(6).get("region").isNull());
        assertFalse(data.toString().contains(directory.toString().replace("\\", "\\\\")));
        assertFalse(data.toString().contains(directory.toString().replace('\\', '/')));
        assertFalse(data.toString().contains("not-archived"));

        JsonNode reportingTokens = reportingStream.get("tokens");
        JsonNode sharedKey = tokens.get(0).get("correspondenceKey");
        assertTrue(sharedKey.isIntegralNumber());
        assertEquals(sharedKey, tokens.get(2).get("correspondenceKey"));
        for (int index = 0; index < 3; index++) {
            assertEquals(sharedKey, reportingTokens.get(index).get("correspondenceKey"));
        }
        JsonNode baselineOnlyKey = tokens.get(3).get("correspondenceKey");
        JsonNode reportingOnlyKey = reportingTokens.get(3).get("correspondenceKey");
        assertTrue(baselineOnlyKey.isIntegralNumber());
        assertTrue(reportingOnlyKey.isIntegralNumber());
        assertNotEquals(sharedKey, baselineOnlyKey);
        assertNotEquals(sharedKey, reportingOnlyKey);
        assertNotEquals(baselineOnlyKey, reportingOnlyKey);

        JsonNode unavailableKey = tokens.get(4).get("correspondenceKey");
        assertTrue(unavailableKey.isIntegralNumber());
        assertNotEquals(sharedKey, unavailableKey);
        assertEquals(unavailableKey, reportingTokens.get(4).get("correspondenceKey"));
        assertTrue(tokens.get(5).get("correspondenceKey").isIntegralNumber());
        assertTrue(reportingTokens.get(5).get("correspondenceKey").isIntegralNumber());
        assertNotEquals(tokens.get(5).get("correspondenceKey"), reportingTokens.get(5).get("correspondenceKey"));
        assertTrue(tokens.get(6).get("correspondenceKey").isNull());
    }

    @Test
    void missingArchivedSourceRetainsCorrespondenceKeys() throws Exception {
        var first = submission("first");
        var second = submission("second");
        var baseline = new ConfigurationResult(List.of(), result(false, first, second));
        File file = first.getFiles().iterator().next();
        first.setTokenList(List.of(token(file, 1), Token.fileEnd(file)));
        var reporting = result(true, first, second);
        var normalized = new ConfigurationResult(List.of(), reporting);
        reporting.setNormalizationAnalysis(NormalizationSnapshot.from(new NormalizationAnalysis.Result(List.of(baseline, normalized), normalized)));
        Files.delete(file.toPath());

        JsonNode data = json(export(reporting), "normalization/first.json");

        JsonNode baselineToken = data.get("streams").get(0).get("tokens").get(0);
        JsonNode reportingToken = data.get("streams").get(1).get("tokens").get(0);
        assertTrue(baselineToken.get("region").isNull());
        assertTrue(reportingToken.get("region").isNull());
        assertTrue(baselineToken.get("correspondenceKey").isIntegralNumber());
        assertEquals(baselineToken.get("correspondenceKey"), reportingToken.get("correspondenceKey"));
    }

    @Test
    void identityKeysSurviveDifferentArchivedRegionsWithoutExportingIdentity() throws Exception {
        var first = submission("first");
        var second = submission("second");
        File file = first.getFiles().iterator().next();
        first.setTokenList(List.of(Token.fileEnd(file), identityToken(file, 1, "private-node-anchor"), identityToken(file, 1, null)));
        var baseline = new ConfigurationResult(List.of(), result(false, first, second));
        first.setTokenList(List.of(identityToken(file, 8, "private-node-anchor"), identityToken(file, 8, null), Token.fileEnd(file)));
        var reporting = result(true, first, second);
        var normalized = new ConfigurationResult(List.of(), reporting);
        reporting.setNormalizationAnalysis(NormalizationSnapshot.from(new NormalizationAnalysis.Result(List.of(baseline, normalized), normalized)));

        JsonNode data = json(export(reporting), "normalization/first.json");

        JsonNode baselineTokens = data.get("streams").get(0).get("tokens");
        JsonNode reportingTokens = data.get("streams").get(1).get("tokens");
        JsonNode baselineToken = baselineTokens.get(1);
        JsonNode reportingToken = reportingTokens.get(0);
        assertEquals(1, baselineToken.get("index").asInt());
        assertEquals(0, reportingToken.get("index").asInt());
        assertEquals(1, baselineToken.get("region").get("startLine").asInt());
        assertEquals(8, reportingToken.get("region").get("startLine").asInt());
        assertTrue(baselineToken.get("correspondenceKey").isIntegralNumber());
        assertEquals(baselineToken.get("correspondenceKey"), reportingToken.get("correspondenceKey"));
        assertTrue(baselineTokens.get(0).get("correspondenceKey").isNull());
        assertTrue(reportingTokens.get(2).get("correspondenceKey").isNull());
        assertFalse(baselineTokens.get(2).get("region").isNull());
        assertTrue(baselineTokens.get(2).get("correspondenceKey").isNull());
        assertFalse(reportingTokens.get(1).get("region").isNull());
        assertTrue(reportingTokens.get(1).get("correspondenceKey").isNull());

        assertEquals(Set.of("streams"), fields(data));
        assertEquals(Set.of("index", "type", "region", "transformations", "fileEnd", "correspondenceKey"), fields(baselineToken));
        assertFalse(data.toString().contains("private-node-anchor"));
    }

    @Test
    void identityKeysAreRetainedWhenArchivedRegionsAreUnavailable() throws Exception {
        var first = spy(submission("first"));
        var second = submission("second");
        File file = first.getFiles().iterator().next();
        doReturn(Map.of(file, 2)).when(first).getTokenCountPerFile();
        File unarchived = directory.resolve("private-unarchived.java").toFile();
        first.setTokenList(List.of(identityToken(unarchived, 1, "unarchived"), identityToken(file, 1, "archived")));
        var baseline = new ConfigurationResult(List.of(), result(false, first, second));
        first.setTokenList(List.of(identityToken(file, 8, "unarchived"), identityToken(file, 8, "archived")));
        var reporting = result(true, first, second);
        var normalized = new ConfigurationResult(List.of(), reporting);
        reporting.setNormalizationAnalysis(NormalizationSnapshot.from(new NormalizationAnalysis.Result(List.of(baseline, normalized), normalized)));

        JsonNode data = json(export(reporting), "normalization/first.json");

        JsonNode baselineTokens = data.get("streams").get(0).get("tokens");
        JsonNode reportingTokens = data.get("streams").get(1).get("tokens");
        assertTrue(baselineTokens.get(0).get("region").isNull());
        assertEquals("first/nested/Main.java", reportingTokens.get(0).get("region").get("file").asText());
        for (int index = 0; index < 2; index++) {
            JsonNode key = baselineTokens.get(index).get("correspondenceKey");
            assertTrue(key.isIntegralNumber());
            assertEquals(key, reportingTokens.get(index).get("correspondenceKey"));
        }
        assertNotEquals(baselineTokens.get(0).get("correspondenceKey"), baselineTokens.get(1).get("correspondenceKey"));
        assertFalse(data.toString().contains("private-unarchived"));
        assertFalse(data.toString().contains("\"unarchived\""));
        assertFalse(data.toString().contains("\"archived\""));
        assertFalse(data.toString().contains(directory.toString().replace("\\", "\\\\")));
        assertFalse(data.toString().contains(directory.toString().replace('\\', '/')));
    }

    @Test
    void supportsAdditionalConfigurationsAndDoesNotInventMissingStreams() throws Exception {
        var first = submission("first");
        var second = submission("second");
        var third = submission("third");
        File file = second.getFiles().iterator().next();
        second.setTokenList(List.of(identityToken(file, 1, "private-common-anchor"), Token.fileEnd(file)));
        var baseline = new ConfigurationResult(List.of(), result(false, first, second));
        second.setTokenList(
                List.of(identityToken(file, 4, "private-common-anchor"), identityToken(file, 4, "private-added-anchor"), Token.fileEnd(file)));
        var middle = new ConfigurationResult(List.of(() -> "middle"), result(true, second, third));
        second.setTokenList(
                List.of(identityToken(file, 8, "private-common-anchor"), identityToken(file, 8, "private-added-anchor"), Token.fileEnd(file)));
        var reporting = new ConfigurationResult(List.of(() -> "last"), result(true, first, second, third));
        var snapshot = NormalizationSnapshot.from(new NormalizationAnalysis.Result(List.of(baseline, middle, reporting), reporting));
        var writer = new TestableReportWriter();
        new NormalizationReportWriter(writer).write(snapshot, Map.of(first.getRoot().toPath(), "first", second.getRoot().toPath(), "second",
                third.getRoot().toPath(), "third", directory.resolve("missing"), "missing"), Map.of());

        JsonNode metadata = json(writer, "normalizationAnalysis.json");
        assertEquals(3, metadata.get("configurations").size());
        assertEquals(Set.of("first", "second", "third"), fields(metadata.get("submissions")));
        JsonNode firstData = json(writer, "normalization/first.json");
        JsonNode secondData = json(writer, "normalization/second.json");
        JsonNode thirdData = json(writer, "normalization/third.json");
        String baselineId = snapshot.configurations().get(0).id();
        String middleId = snapshot.configurations().get(1).id();
        String reportingId = snapshot.configurations().get(2).id();
        assertEquals(List.of(baselineId, reportingId), firstData.get("streams").findValuesAsText("configurationId"));
        assertEquals(List.of(baselineId, middleId, reportingId), secondData.get("streams").findValuesAsText("configurationId"));
        assertEquals(List.of(middleId, reportingId), thirdData.get("streams").findValuesAsText("configurationId"));
        for (JsonNode data : List.of(firstData, secondData, thirdData)) {
            assertEquals(Set.of("streams"), fields(data));
            JsonNode key = data.get("streams").get(0).get("tokens").get(0).get("correspondenceKey");
            assertTrue(key.isIntegralNumber());
            for (JsonNode stream : data.get("streams")) {
                JsonNode token = stream.get("tokens").get(0);
                assertTrue(token.get("region").isNull());
                assertEquals(key, token.get("correspondenceKey"));
            }
        }

        JsonNode secondStreams = secondData.get("streams");
        assertEquals(2, secondStreams.get(0).get("tokens").size());
        JsonNode addedKey = secondStreams.get(1).get("tokens").get(1).get("correspondenceKey");
        assertTrue(addedKey.isIntegralNumber());
        assertEquals(addedKey, secondStreams.get(2).get("tokens").get(1).get("correspondenceKey"));
        assertNotEquals(secondStreams.get(0).get("tokens").get(0).get("correspondenceKey"), addedKey);
        assertFalse(secondData.toString().contains("private-common-anchor"));
        assertFalse(secondData.toString().contains("private-added-anchor"));
    }

    @Test
    void snapshotIsDetachedImmutableAndContainsNoResultCycle() throws Exception {
        var first = submission("first");
        Path root = first.getRoot().toPath();
        var baseline = new ConfigurationResult(List.of(), result(false, first));
        List<Normalization> selection = new ArrayList<>(List.of(() -> "first", () -> "second"));
        var reporting = new ConfigurationResult(selection, result(true, first));
        var snapshot = NormalizationSnapshot.from(new NormalizationAnalysis.Result(List.of(baseline, reporting), reporting));
        first.setTokenList(List.of());
        selection.clear();
        reporting.result().setNormalizationAnalysis(snapshot);

        assertEquals(List.of("first", "second"), snapshot.configurations().get(1).normalizations());
        assertEquals(2, snapshot.submissions().get(root).streams().get(0).tokens().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.submissions().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.configurations().clear());
        assertDetached(snapshot);
    }

    @Test
    void runAttachesSnapshotToReportingResultOnly() throws Exception {
        var first = submission("first");
        var baseline = result(false, first);
        var reporting = result(true, first);
        var language = mock(NormalizableLanguage.class);
        when(language.availableNormalizations()).thenReturn(List.of(() -> "selected"));
        when(language.withNormalizations(any())).thenReturn(language);
        try (var jplag = mockStatic(JPlag.class)) {
            jplag.when(() -> JPlag.runConfiguration(any())).thenReturn(baseline, reporting);
            assertSame(reporting, NormalizationAnalysis.run(language, reporting.getOptions()));
        }
        assertTrue(baseline.getNormalizationAnalysis().isEmpty());
        assertTrue(reporting.getNormalizationAnalysis().isPresent());
    }

    private void assertDetached(Object value) throws Exception {
        if (value == null || value instanceof Path) {
            return;
        }
        assertFalse(value instanceof JPlagResult || value instanceof ConfigurationResult || value instanceof Token);
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                assertDetached(entry.getKey());
                assertDetached(entry.getValue());
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                assertDetached(element);
            }
        } else if (value.getClass().isRecord()) {
            for (var component : value.getClass().getRecordComponents()) {
                assertDetached(component.getAccessor().invoke(value));
            }
        }
    }

    private Submission submission(String name) throws Exception {
        Path root = directory.resolve(name).toAbsolutePath().normalize();
        Path file = root.resolve("nested/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Main {}\n");
        var submission = new Submission(name, root.toFile(), true, List.of(file.toFile()), new JavaLanguage());
        submission.setTokenList(List.of(token(file.toFile(), 1), Token.fileEnd(file.toFile())));
        return submission;
    }

    private JPlagResult result(boolean normalize, Submission... submissions) {
        var submissionSet = mock(SubmissionSet.class);
        when(submissionSet.getSubmissions()).thenReturn(List.of(submissions));
        when(submissionSet.getInvalidSubmissions()).thenReturn(List.of());
        var options = new JPlagOptions(new JavaLanguage(), Set.of(), Set.of()).with().normalize(normalize).build();
        List<JPlagComparison> comparisons = submissions.length < 2 ? List.of()
                : List.of(new JPlagComparison(submissions[0], submissions[1], List.of(), List.of()));
        var result = spy(new JPlagResult(comparisons, submissionSet, 0, options));
        doReturn(List.of()).when(result).getClusteringResult();
        return result;
    }

    private TestableReportWriter export(JPlagResult result) {
        var writer = new TestableReportWriter();
        new ReportObjectFactory(writer).createAndSaveReport(result);
        return writer;
    }

    private Token token(File file, int line) {
        return new Token(STATEMENT, file, line, 2, line + 1, 7, Token.NO_VALUE);
    }

    private Token identityToken(File file, int line, String identity) {
        class IdentifiedToken extends Token implements IdentityAwareToken {
            IdentifiedToken() {
                super(STATEMENT, file, line, 2, line + 1, 7, Token.NO_VALUE);
            }

            @Override
            public Optional<String> identity() {
                return Optional.ofNullable(identity);
            }
        }
        return new IdentifiedToken();
    }

    private Token provenanceToken(File file) {
        class TransformedToken extends Token implements ProvenanceAwareToken {
            TransformedToken() {
                super(STATEMENT, file, 1, 2, 2, 7, Token.NO_VALUE);
            }

            @Override
            public java.util.SequencedSet<Provenance> provenances() {
                return new LinkedHashSet<>(List.of(new Provenance("Z"), new Provenance("A")));
            }
        }
        return new TransformedToken();
    }

    private JsonNode json(TestableReportWriter writer, String path) {
        Object entry = writer.getJsonEntry(Path.of(path));
        assertNotNull(entry, path);
        return MAPPER.valueToTree(entry);
    }

    private Set<String> fields(JsonNode node) {
        Set<String> fields = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}
