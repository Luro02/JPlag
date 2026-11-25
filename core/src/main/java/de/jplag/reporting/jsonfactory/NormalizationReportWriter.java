package de.jplag.reporting.jsonfactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import de.jplag.analysis.NormalizationSnapshot;
import de.jplag.analysis.SourceRegion;
import de.jplag.reporting.FilePathUtil;
import de.jplag.reporting.reportobject.writer.JPlagResultWriter;

/**
 * Exports optional normalization inspector data using only archived source paths.
 */
public final class NormalizationReportWriter {
    private final JPlagResultWriter resultWriter;

    /**
     * Creates an inspector report writer.
     * @param resultWriter the archive writer
     */
    public NormalizationReportWriter(JPlagResultWriter resultWriter) {
        this.resultWriter = resultWriter;
    }

    /**
     * Writes inspector metadata and the submissions included in report comparisons.
     * @param snapshot the detached analysis data
     * @param submissionIds the reported submission roots and identifiers
     * @param archivedFiles physical source paths mapped to report-relative file names per submission
     */
    public void write(NormalizationSnapshot snapshot, Map<Path, String> submissionIds, Map<Path, Map<Path, String>> archivedFiles) {
        Map<String, String> submissions = new LinkedHashMap<>();
        submissionIds.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(entry -> {
            var submission = snapshot.submissions().get(entry.getKey());
            if (submission == null) {
                return;
            }
            Path path = Path.of("normalization", entry.getValue() + ".json");
            Map<Path, String> files = archivedFiles.getOrDefault(entry.getKey(), Map.of());
            List<Stream> streams = submission.streams().stream().map(stream -> convertStream(stream, files)).toList();
            resultWriter.addJsonEntry(new SubmissionData(streams), path);
            submissions.put(entry.getValue(), FilePathUtil.pathAsZipPath(path));
        });
        resultWriter.addJsonEntry(new Metadata(snapshot.configurations(), snapshot.baselineConfigurationId(), submissions),
                Path.of("normalizationAnalysis.json"));
    }

    private Stream convertStream(NormalizationSnapshot.Stream stream, Map<Path, String> files) {
        List<TokenData> tokens = IntStream.range(0, stream.tokens().size()).mapToObj(index -> {
            var token = stream.tokens().get(index);
            return new TokenData(index, token.type(), region(token.region(), files), token.transformations(), token.fileEnd(),
                    token.correspondenceKey());
        }).toList();
        return new Stream(stream.configurationId(), tokens);
    }

    private Region region(SourceRegion source, Map<Path, String> files) {
        if (source == null || !files.containsKey(source.file())) {
            return null;
        }
        return new Region(files.get(source.file()), source.startLine(), source.startColumn(), source.endLine(), source.endColumn());
    }

    private record Metadata(List<NormalizationSnapshot.Configuration> configurations, String baselineConfigurationId,
            Map<String, String> submissions) {
    }

    private record SubmissionData(List<Stream> streams) {
    }

    private record Stream(String configurationId, List<TokenData> tokens) {
    }

    private record TokenData(int index, String type, Region region, List<String> transformations, boolean fileEnd, Integer correspondenceKey) {
    }

    private record Region(String file, int startLine, int startColumn, int endLine, int endColumn) {
    }
}
