package de.jplag.analysis;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import de.jplag.JPlagResult;
import de.jplag.Normalization;
import de.jplag.Submission;
import de.jplag.Token;

public final class ConfigurationResult {
    private final List<Normalization> normalizations;
    private final JPlagResult result;
    private final Map<Path, List<Token>> tokensBySubmission = new LinkedHashMap<>();

    public ConfigurationResult(List<? extends Normalization> normalizations, JPlagResult result) {
        this.normalizations = List.copyOf(normalizations);
        this.result = result;
        for (Submission submission : result.getSubmissions().getSubmissions()) {
            Path root = submission.getRoot().toPath().toAbsolutePath().normalize();
            tokensBySubmission.put(root, List.copyOf(submission.getTokenList()));
        }
    }

    public List<Normalization> normalizations() {
        return normalizations;
    }

    public JPlagResult result() {
        return result;
    }

    public Map<Path, TokenCorrespondence> correspondences(ConfigurationResult other) {
        return tokensBySubmission.entrySet().stream().filter(entry -> other.tokensBySubmission.containsKey(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> TokenCorrespondence.between(entry.getValue(), other.tokensBySubmission.get(entry.getKey())),
                        (existing, _) -> existing, LinkedHashMap::new));
    }
}
