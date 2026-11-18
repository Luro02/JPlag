package de.jplag.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import de.jplag.SharedTokenType;
import de.jplag.Token;

public final class TokenCorrespondence {
    private final Side left;
    private final Side right;
    private final List<Group> groups;

    private TokenCorrespondence(List<? extends Token> left, List<? extends Token> right) {
        List<Token> leftTokens = List.copyOf(left);
        List<Token> rightTokens = List.copyOf(right);

        var leftByRegion = positionsByRegion(leftTokens);
        var rightByRegion = positionsByRegion(rightTokens);

        groups = leftByRegion.entrySet().stream().filter(entry -> rightByRegion.containsKey(entry.getKey()))
                .map(entry -> new Group(entry.getKey(), entry.getValue(), rightByRegion.get(entry.getKey()))).toList();

        this.left = new Side(leftTokens, unmatchedPositions(leftByRegion, rightByRegion));
        this.right = new Side(rightTokens, unmatchedPositions(rightByRegion, leftByRegion));
    }

    private static Set<Integer> unmatchedPositions(Map<SourceRegion, ? extends Set<Integer>> positions,
            Map<SourceRegion, Set<Integer>> counterparts) {
        return positions.entrySet().stream().filter(entry -> !counterparts.containsKey(entry.getKey())).flatMap(entry -> entry.getValue().stream())
                .collect(Collectors.toSet());
    }

    /**
     * Groups tokens from two streams by exact source region, ignoring file-end markers.
     * @param left the left token stream
     * @param right the right token stream
     * @return the correspondence and snapshots of both token streams
     */
    public static TokenCorrespondence between(List<? extends Token> left, List<? extends Token> right) {
        return new TokenCorrespondence(left, right);
    }

    public Side left() {
        return left;
    }

    public Side right() {
        return right;
    }

    public List<Group> groups() {
        return groups;
    }

    private static Map<SourceRegion, Set<Integer>> positionsByRegion(List<? extends Token> tokens) {
        return IntStream.range(0, tokens.size())
                // file end tokens do not have a source position, therefore they are skipped
                .filter(position -> tokens.get(position).getType() != SharedTokenType.FILE_END).boxed()
                // for each token (position is the index in the list), this maps the region to the position
                .flatMap(position -> SourceRegion.from(tokens.get(position)).stream().map(region -> Map.entry(region, position)))
                // there may be multiple tokens at the same region which are grouped here
                .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new, Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));
    }

    public record Side(List<Token> tokens, Set<Integer> unmatchedPositions) {
        public Side {
            tokens = List.copyOf(tokens);
            unmatchedPositions = Set.copyOf(unmatchedPositions);
        }
    }

    public record Group(SourceRegion region, Set<Integer> leftPositions, Set<Integer> rightPositions) {
        public Group {
            leftPositions = Set.copyOf(leftPositions);
            rightPositions = Set.copyOf(rightPositions);
        }
    }
}
