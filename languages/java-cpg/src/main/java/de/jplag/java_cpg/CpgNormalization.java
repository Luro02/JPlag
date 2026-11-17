package de.jplag.java_cpg;

import de.jplag.Normalization;

/**
 * Selectable CPG normalizations implemented by passes rather than graph transformations.
 */
public enum CpgNormalization implements Normalization {
    /**
     * Enables DFG-based statement reordering and removal of unused statements.
     */
    DFG_NORMALIZATION
}
