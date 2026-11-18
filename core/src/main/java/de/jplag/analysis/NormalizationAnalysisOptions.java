package de.jplag.analysis;

import io.soabase.recordbuilder.core.RecordBuilder;

/**
 * Options for analyzing match changes across normalization configurations, independently of single-run normalization.
 * @param enabled global switch to enable or disable the analysis feature
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
