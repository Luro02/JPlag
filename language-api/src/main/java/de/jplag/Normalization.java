package de.jplag;

/**
 * Identifies a language-specific normalization. A {@link NormalizableLanguage} exposes its selectable normalizations
 * through {@link NormalizableLanguage#availableNormalizations()}.
 */
public interface Normalization {
    /**
     * Returns the name identifying this normalization, unique within its language.
     * @return the normalization name
     */
    String name();
}
