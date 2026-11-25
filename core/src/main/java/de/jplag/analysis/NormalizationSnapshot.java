package de.jplag.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import de.jplag.Normalization;
import de.jplag.Provenance;
import de.jplag.ProvenanceAwareToken;
import de.jplag.SharedTokenType;
import de.jplag.Token;

/**
 * An immutable, self-contained snapshot of a normalization analysis run.
 * <p>
 * Copies token metadata into plain value objects so the snapshot can be serialized or inspected without holding
 * references to mutable parser, AST, or comparison objects.
 * @param configurations the normalization configurations evaluated, with the baseline first
 * @param baselineConfigurationId the ID of the baseline configuration
 * @param submissions captured token streams, keyed by normalized submission root path
 */
public record NormalizationSnapshot(List<Configuration> configurations, String baselineConfigurationId, Map<Path, Submission> submissions) {
    public NormalizationSnapshot {
        configurations = List.copyOf(configurations);
        submissions = Collections.unmodifiableMap(new LinkedHashMap<>(submissions));
    }

    /**
     * Builds a detached snapshot from a completed normalization analysis.
     * @param analysis the completed analysis result (must contain at least the baseline run)
     * @return an immutable snapshot of all runs and submissions
     */
    public static NormalizationSnapshot from(NormalizationAnalysis.Result analysis) {
        List<ConfigurationResult> rawRuns = analysis.runs();
        List<IndexedRun> runs = IntStream.range(0, rawRuns.size())
                .mapToObj(index -> IndexedRun.from(index, rawRuns.get(index), analysis.reportingRun())).toList();
        List<Configuration> configurations = runs.stream().map(IndexedRun::config).toList();
        String baselineId = configurations.getFirst().id();

        Map<Path, Submission> submissions = new LinkedHashMap<>();
        rawRuns.stream().flatMap(run -> run.tokensBySubmission().keySet().stream()).distinct()
                .forEach(root -> submissions.put(root, captureSubmission(root, runs)));
        return new NormalizationSnapshot(configurations, baselineId, submissions);
    }

    private static Submission captureSubmission(Path root, List<IndexedRun> runs) {
        List<Stream> streams = new ArrayList<>();
        // One lookup across all configurations also covers tokens absent from the baseline.
        Map<SourceRegion, Integer> keys = new HashMap<>();
        for (IndexedRun entry : runs) {
            List<Token> tokens = entry.run().tokensBySubmission().get(root);
            if (tokens != null) {
                streams.add(captureStream(entry.config().id(), tokens, keys));
            }
        }
        return new Submission(streams);
    }

    private static Stream captureStream(String configurationId, List<Token> tokens, Map<SourceRegion, Integer> keys) {
        List<TokenData> captured = new ArrayList<>();

        for (Token token : tokens) {
            boolean fileEnd = token.getType() == SharedTokenType.FILE_END;
            SourceRegion region = fileEnd ? null : SourceRegion.from(token).orElse(null);
            Integer correspondenceKey = region == null ? null : keys.computeIfAbsent(region, _ -> keys.size());

            String type = token.getType() instanceof Enum<?> enumType ? enumType.name() : token.getType().getDescription();
            List<String> transformations = token instanceof ProvenanceAwareToken aware
                    ? aware.provenances().stream().map(Provenance::transformationName).toList()
                    : List.of();
            captured.add(new TokenData(type, region, transformations, fileEnd, correspondenceKey));
        }
        return new Stream(configurationId, captured);
    }

    /**
     * Metadata describing a single normalization configuration run.
     * @param id unique configuration identifier (e.g., {@code "configuration-0"})
     * @param name human-readable label (e.g., {@code "Baseline"}, {@code "Fully normalized"})
     * @param normalizations names of the active normalizations in execution order
     * @param normalize whether normalization was enabled for this run
     */
    public record Configuration(String id, String name, List<String> normalizations, boolean normalize) {
        public Configuration {
            normalizations = List.copyOf(normalizations);
        }
    }

    private record IndexedRun(Configuration config, ConfigurationResult run) {
        private static IndexedRun from(int index, ConfigurationResult run, ConfigurationResult reportingRun) {
            String name = "Configuration " + index;
            if (index == 0) {
                name = "Baseline";
            } else if (run == reportingRun) {
                name = "Fully normalized";
            }

            List<String> normalizationNames = run.normalizations().stream().map(Normalization::name).toList();
            var config = new Configuration("configuration-" + index, name, normalizationNames, run.result().getOptions().normalize());

            return new IndexedRun(config, run);
        }
    }

    /**
     * Captured token streams for a single submission. Tokens with the same non-null correspondence key belong together
     * across configurations when their source regions match exactly, even when their type or stream position differs. Keys
     * have no meaning outside this submission's snapshot.
     * @param streams token streams for each configuration that processed this submission
     */
    public record Submission(List<Stream> streams) {
        public Submission {
            streams = List.copyOf(streams);
        }
    }

    /**
     * Captured token sequence for one submission under one configuration.
     * @param configurationId ID of the configuration that produced this stream
     * @param tokens captured tokens in stream index order, including trailing {@code FILE_END} markers
     */
    public record Stream(String configurationId, List<TokenData> tokens) {
        public Stream {
            tokens = List.copyOf(tokens);
        }
    }

    /**
     * Detached metadata for a single token.
     * @param type token type name (enum constant name or type description)
     * @param region source code span, or {@code null} for {@code FILE_END} or synthetic tokens without a location
     * @param transformations names of transformations that produced or modified this token
     * @param fileEnd {@code true} if this token marks the end of a file
     * @param correspondenceKey opaque key shared by corresponding tokens in this submission, or {@code null} if excluded
     * from correspondence (not merely unmatched). Keys are based on exact source regions. File-end tokens and tokens
     * without a source region are always excluded.
     */
    public record TokenData(String type, SourceRegion region, List<String> transformations, boolean fileEnd, Integer correspondenceKey) {
        public TokenData {
            transformations = List.copyOf(transformations);
        }
    }
}
