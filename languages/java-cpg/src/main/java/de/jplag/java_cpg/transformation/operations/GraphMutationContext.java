package de.jplag.java_cpg.transformation.operations;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge;
import de.jplag.java_cpg.transformation.ProvenanceTracker;

/**
 * Mutable graph-editing state owned by one translation, whose passes execute sequentially.
 */
public final class GraphMutationContext implements AutoCloseable {
    private final Node dummyNeighbor;
    private final Set<PropertyEdge<Node>> temporaryEdges;
    private final ProvenanceTracker tracker;
    private volatile boolean closed;

    /**
     * Creates independent intermediary state for one translation.
     */
    public GraphMutationContext() {
        this.dummyNeighbor = new Node();
        this.temporaryEdges = Collections.newSetFromMap(new IdentityHashMap<>());
        this.closed = false;
        this.tracker = new ProvenanceTracker();
    }

    /**
     * Returns this translation's intermediary node.
     * @return the intermediary node
     */
    public Node getDummyNeighbor() {
        return dummyNeighbor;
    }

    /**
     * Returns the provenance tracked for this translation.
     * @return translation-local transformation associations
     */
    public ProvenanceTracker getProvenanceTracker() {
        return tracker;
    }

    /**
     * Registers an edge entering the dummy, retaining it for final reciprocal cleanup.
     * @param edge incoming edge
     */
    void registerIncomingEdge(PropertyEdge<Node> edge) {
        temporaryEdges.add(edge);
        dummyNeighbor.addPrevEOG(edge);
    }

    /**
     * Registers an edge leaving the dummy, retaining it for final reciprocal cleanup.
     * @param edge outgoing edge
     */
    void registerOutgoingEdge(PropertyEdge<Node> edge) {
        temporaryEdges.add(edge);
        dummyNeighbor.addNextEOG(edge);
    }

    /**
     * Releases saved nodes and remaining intermediary edges after analysis has completed.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        // Pass cleanup may already have removed an edge from the dummy's own lists.
        temporaryEdges.addAll(dummyNeighbor.getNextEOGEdges());
        temporaryEdges.addAll(dummyNeighbor.getPrevEOGEdges());
        temporaryEdges.forEach(edge -> {
            if (edge.getStart() == dummyNeighbor || edge.getEnd() == dummyNeighbor) {
                TransformationUtil.removeEogEdge(edge);
            }
        });
        dummyNeighbor.getNextEOGEdges().clear();
        dummyNeighbor.getPrevEOGEdges().clear();
        temporaryEdges.clear();
        tracker.clear();

        closed = true;
    }
}
