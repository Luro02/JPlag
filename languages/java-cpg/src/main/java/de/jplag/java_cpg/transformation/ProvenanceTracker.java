package de.jplag.java_cpg.transformation;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.SequencedSet;
import java.util.function.Function;

import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker;
import de.jplag.Provenance;

import kotlin.Unit;

/**
 * Tracks node associations during the sequential transformations of one translation.
 */
public final class ProvenanceTracker {
    private static final Function<? super Node, ? extends SequencedSet<Provenance>> MAPPING = _ -> new LinkedHashSet<>();
    /**
     * This field tracks the provenance associated with a given node.
     * <p>
     * It tracks which node was touched by a transformation.
     */
    private final Map<Node, SequencedSet<Provenance>> associations;
    private Provenance current;

    public ProvenanceTracker() {
        this.associations = new IdentityHashMap<>();
    }

    public void begin(GraphTransformation transformation) {
        this.current = new Provenance(transformation.name());
    }

    /**
     * Records that the given node is associated with the current transformation.
     * @param node the node to be associated.
     */
    public void record(Node node) {
        if (this.current == null) {
            return;
        }

        this.associations.computeIfAbsent(node, MAPPING).add(this.current);
    }

    /**
     * Associates the node and its descendants with the current transformation.
     * @param node the node of which to associate the children
     */
    public void recordSubtree(Node node) {
        if (this.current == null) {
            return;
        }

        var walker = new SubgraphWalker.IterativeGraphWalker();
        walker.registerOnNodeVisit((visited, _) -> {
            record(visited);
            return Unit.INSTANCE;
        });
        walker.iterate(node);
    }

    /**
     * Associates the replacement with all associations of the original node.
     * @param original the original node to copy the associations from
     * @param replacement the node that replaces the original node
     */
    public void inherit(Node original, Node replacement) {
        if (this.current == null) {
            return;
        }

        var inherited = this.associations.get(original);

        if (inherited != null) {
            this.associations.computeIfAbsent(replacement, MAPPING).addAll(inherited);
        }
    }

    public SequencedSet<Provenance> get(Node node) {
        return this.associations.getOrDefault(node, new LinkedHashSet<>());
    }

    public void end() {
        this.current = null;
    }

    public void clear() {
        this.current = null;
        this.associations.clear();
    }
}
