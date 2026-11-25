package de.jplag.analysis;

import java.util.List;

import de.jplag.JPlag;
import de.jplag.JPlagResult;
import de.jplag.NormalizableLanguage;
import de.jplag.Normalization;
import de.jplag.exceptions.ExitException;
import de.jplag.options.JPlagOptions;

/**
 * Entry point for analyzing match changes across normalization configurations.
 */
public final class NormalizationAnalysis {
    private NormalizationAnalysis() {
    }

    public static JPlagResult run(NormalizableLanguage language, JPlagOptions options) throws ExitException {
        Result analysis = analyze(language, options);
        JPlagResult result = analysis.reportingRun().result();
        result.setNormalizationAnalysis(NormalizationSnapshot.from(analysis));
        return result;
    }

    public static Result analyze(NormalizableLanguage language, JPlagOptions options) throws ExitException {
        // TODO: Disable token normalizations in the baseline as well.
        var baseline = execute(language, options, List.of(), false);
        var fullyEnabled = execute(language, options, List.copyOf(language.availableNormalizations()), true);
        return new Result(List.of(baseline, fullyEnabled), fullyEnabled);
    }

    private static ConfigurationResult execute(NormalizableLanguage language, JPlagOptions options, List<Normalization> normalizations,
            boolean normalize) throws ExitException {
        var configuredOptions = options.with().language(language.withNormalizations(normalizations)).normalize(normalize).build();
        return new ConfigurationResult(normalizations, JPlag.runConfiguration(configuredOptions));
    }

    public record Result(List<ConfigurationResult> runs, ConfigurationResult reportingRun) {
        public Result {
            runs = List.copyOf(runs);
        }
    }
}
