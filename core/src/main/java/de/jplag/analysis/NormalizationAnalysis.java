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
        return analyze(language, options).reportingRun().result();
    }

    public static Result analyze(NormalizableLanguage language, JPlagOptions options) throws ExitException {
        // TODO: Disable token normalizations in the baseline as well.
        var baseline = execute(language, options, new Configuration(List.of(), false));
        var fullyEnabled = execute(language, options, new Configuration(List.copyOf(language.availableNormalizations()), true));
        return new Result(List.of(baseline, fullyEnabled), fullyEnabled);
    }

    private static ConfigurationResult execute(NormalizableLanguage language, JPlagOptions options, Configuration configuration)
            throws ExitException {
        var configuredOptions = options.with().language(language.withNormalizations(configuration.normalizations()))
                .normalize(configuration.normalize()).build();
        return new ConfigurationResult(configuration.normalizations(), JPlag.runConfiguration(configuredOptions));
    }

    private record Configuration(List<Normalization> normalizations, boolean normalize) {
    }

    public record Result(List<ConfigurationResult> runs, ConfigurationResult reportingRun) {
        public Result {
            runs = List.copyOf(runs);
        }
    }
}
