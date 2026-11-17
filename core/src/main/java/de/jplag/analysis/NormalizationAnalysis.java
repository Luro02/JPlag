package de.jplag.analysis;

import java.util.List;

import de.jplag.JPlag;
import de.jplag.JPlagResult;
import de.jplag.NormalizableLanguage;
import de.jplag.exceptions.ExitException;
import de.jplag.options.JPlagOptions;

/**
 * Entry point for analyzing match changes across normalization configurations.
 */
public final class NormalizationAnalysis {
    private NormalizationAnalysis() {
    }

    /**
     * Runs comparisons with all optional normalizations disabled, then with all available normalizations enabled.
     * Obligatory preparation remains active in both runs. Each configuration parses submissions independently and preserves
     * the remaining options. The disabled result is discarded for now; correlation is not implemented yet.
     * @param language the language providing the selectable normalizations
     * @param options the input and analysis configuration
     * @return the comparison result with all available normalizations enabled
     * @throws ExitException if either configuration fails
     */
    public static JPlagResult run(NormalizableLanguage language, JPlagOptions options) throws ExitException {
        var disabledOptions = options.with().language(language.withNormalizations(List.of())).normalize(false).build();
        JPlag.runConfiguration(disabledOptions);

        var enabledOptions = options.with().language(language.withNormalizations(language.availableNormalizations())).normalize(true).build();
        return JPlag.runConfiguration(enabledOptions);
    }
}
