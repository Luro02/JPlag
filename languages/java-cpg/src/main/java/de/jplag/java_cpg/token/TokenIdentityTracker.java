package de.jplag.java_cpg.token;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

import de.fraunhofer.aisec.cpg.TranslationResult;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.declarations.TranslationUnitDeclaration;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker;
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation;

/**
 * Tracks stable identities for AST nodes across graph transformations.
 * <p>
 * Before AST rewrites move, replace, or delete nodes, {@link #initialize(TranslationResult)} records each node's
 * original AST path (e.g., {@code /path/to/File.java#/0/2/1}). Later rewrites can pass that identity along to
 * replacement nodes via {@link #inherit(Node, Node)} so generated tokens remain traceable to their original source
 * location.
 */
public final class TokenIdentityTracker {
    private static final Comparator<Node> SOURCE_ORDER = Comparator
            .comparing(Node::getLocation, Comparator.nullsLast(Comparator.comparing(PhysicalLocation::getRegion)))
            .thenComparing(node -> node.getClass().getName()).thenComparing(Node::getName);

    private final Map<Node, String> anchors = new IdentityHashMap<>();

    /**
     * Walks the initial AST and records a deterministic identity for every node.
     * <p>
     * Must be called before any graph transformations run.
     * @param result the parsed translation result to index
     */
    public void initialize(TranslationResult result) {
        anchors.clear();
        // Sort translation units by name to guarantee a deterministic traversal order across runs
        result.getComponents().stream().flatMap(component -> component.getTranslationUnits().stream()).sorted(Comparator.comparing(Node::getName))
                .forEach(this::initialize);
    }

    private void initialize(TranslationUnitDeclaration unit) {
        // Normalize the file path as the root anchor (e.g., "/abs/path/to/File.java#")
        // so node identities remain consistent regardless of relative path representation.
        anchors.put(unit, Path.of(unit.getName().toString()).toAbsolutePath().normalize() + "#");

        // Use an iterative walker to avoid stack overflows
        var walker = new SubgraphWalker.IterativeGraphWalker();
        walker.setStrategy(node -> {
            // Children have to be sorted too to ensure sibling indices are deterministic
            var children = node.getAstChildren().stream().sorted(SOURCE_ORDER).toList();

            var prefix = anchors.get(node) + "/";
            for (int i = 0; i < children.size(); i++) {
                anchors.putIfAbsent(children.get(i), prefix + i);
            }
            return children.iterator();
        });
        walker.iterate(unit);
    }

    /**
     * Copies the identity of an original node to a replacement node created during an AST rewrite.
     * <p>
     * If {@code original} has no tracked identity, this method does nothing.
     * @param original the node being replaced
     * @param replacement the newly created node that takes its place
     */
    public void inherit(Node original, Node replacement) {
        String anchor = anchors.get(original);
        if (anchor != null) {
            anchors.put(replacement, anchor);
        }
    }

    /**
     * Looks up the tracked token identity for a node.
     * @param node the AST node emitting a token
     * @param endToken {@code true} if this represents the closing token of the node (e.g., a closing brace)
     * @return the tracked identity, or empty if the node was synthesized without an original anchor
     */
    public Optional<String> get(Node node, boolean endToken) {
        return Optional.ofNullable(anchors.get(node)).map(anchor -> anchor + (endToken ? "/end" : "/start"));
    }

    /**
     * Clears all stored node mappings to allow the AST to be garbage-collected.
     */
    public void clear() {
        anchors.clear();
    }
}
