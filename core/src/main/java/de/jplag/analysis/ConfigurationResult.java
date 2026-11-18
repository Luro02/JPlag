package de.jplag.analysis;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.jplag.JPlagResult;
import de.jplag.Normalization;
import de.jplag.Submission;
import de.jplag.Token;

public final class ConfigurationResult {
    private final List<Normalization> normalizations;
    private final JPlagResult result;
    private final Map<Path, List<Token>> tokensBySubmission;

    public ConfigurationResult(Collection<? extends Normalization> normalizations, JPlagResult result) {
        this.normalizations = List.copyOf(normalizations);
        this.result = result;

        Map<Path, List<Token>> streams = new LinkedHashMap<>();
        for (Submission submission : result.getSubmissions().getSubmissions()) {
            Path root = submission.getRoot().toPath().toAbsolutePath().normalize();
            streams.put(root, List.copyOf(submission.getTokenList()));
        }
        this.tokensBySubmission = Collections.unmodifiableMap(streams);
    }

    public List<Normalization> normalizations() {
        return normalizations;
    }

    public JPlagResult result() {
        return result;
    }

    /**
     * Returns the captured token streams keyed by normalized submission root.
     * @return the immutable token stream lookup
     */
    Map<Path, List<Token>> tokensBySubmission() {
        return tokensBySubmission;
    }
}
