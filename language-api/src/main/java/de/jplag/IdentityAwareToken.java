package de.jplag;

import java.util.Optional;

/**
 * A token that carries a stable identity across normalization passes.
 * <p>
 * Allows tokens from different normalization configurations to be matched directly even if their source locations or
 * surrounding AST structures change.
 */
public interface IdentityAwareToken {
    /**
     * Returns this token's stable identity.
     * @return the token identity, or empty if this token has no tracked identity (for example, synthetic or file-end
     * tokens)
     */
    Optional<String> identity();
}
