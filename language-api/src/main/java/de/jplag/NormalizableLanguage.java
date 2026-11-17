package de.jplag;

import java.util.List;
import java.util.Set;

/**
 * A {@link Language} that has configurable {@link Normalization}s that can be selectively enabled or disabled.
 * Obligatory preparation remains enabled independently of the selection.
 */
public interface NormalizableLanguage extends Language {
    /**
     * Returns the selectable normalizations in their recommended execution order, excluding obligatory preparation. Each
     * normalization has a name unique within this language.
     * @return the ordered catalogue of supported normalizations
     */
    List<Normalization> availableNormalizations();

    /**
     * Returns an independently configured language with only the selected optional normalizations enabled, retaining
     * obligatory preparation and leaving this instance unchanged. Selection order is preserved subject to the language's
     * execution-phase constraints. The selection is applied when {@link #parse(Set, boolean)} is called with
     * {@code normalize=true}; {@code normalize=false} disables optional normalization regardless of the selection.
     * @param enabledNormalizations the ordered normalizations to enable, drawn from {@link #availableNormalizations()}; an
     * empty list disables all optional normalizations
     * @return an independent copy with the selected normalizations
     * @throws IllegalArgumentException if a selected normalization is not supported
     */
    NormalizableLanguage withNormalizations(List<? extends Normalization> enabledNormalizations);
}
