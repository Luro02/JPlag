package de.jplag.analysis;

import io.soabase.recordbuilder.core.RecordBuilder;

/**
 * Options for analyzing match changes across normalization configurations, independently of single-run normalization.
 * @param enabled whether to run both disabled and fully enabled normalization configurations instead of an ordinary
 * comparison; currently returns only the fully enabled result, without correlating the runs
 */
@RecordBuilder
public record NormalizationAnalysisOptions(boolean enabled) implements NormalizationAnalysisOptionsBuilder.With {
    /** Normalization analysis is disabled by default. */
    private static final boolean DEFAULT_ENABLED = false;

    /**
     * Creates options with normalization analysis disabled.
     */
    public NormalizationAnalysisOptions() {
        this(DEFAULT_ENABLED);
    }
}
