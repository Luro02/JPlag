package de.jplag;

import java.util.SequencedSet;

/**
 * Implemented by tokens that provide additional information about their origin.
 */
@FunctionalInterface
public interface ProvenanceAwareToken {
    /**
     * Returns the provenance associated with this token.
     * @return an immutable list of provenance entries
     */
    SequencedSet<Provenance> provenances();
}
