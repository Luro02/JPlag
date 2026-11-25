package de.jplag.java_cpg.transformation.operations;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.aisec.cpg.TranslationContext;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.declarations.Declaration;
import de.fraunhofer.aisec.cpg.graph.edge.Properties;
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge;
import de.fraunhofer.aisec.cpg.graph.scopes.Scope;
import de.fraunhofer.aisec.cpg.graph.statements.BreakStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ContinueStatement;
import de.fraunhofer.aisec.cpg.graph.statements.GotoStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ReturnStatement;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.UnaryOperator;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker;
import de.jplag.java_cpg.transformation.GraphTransformation;
import de.jplag.java_cpg.transformation.TransformationException;

/**
 * This class is a collection of auxiliary methods related to {@link GraphTransformation}s.
 */
public final class TransformationUtil {

    private static final Logger logger = LoggerFactory.getLogger(TransformationUtil.class);

    private TransformationUtil() {
        /* should not be instantiated */
    }

    static void moveToParentScope(TranslationContext ctx, Node parent, Node child) {
        Scope parentScope = Objects.requireNonNullElse(ctx.getScopeManager().lookupScope(parent), parent.getScope());
        Scope previousScope = child.getScope();
        if (previousScope == parentScope) {
            return;
        }

        // Nested scopes must keep their own bindings, only nodes sharing the moved root's enclosing scope should move.
        for (Node node : SubgraphWalker.INSTANCE.flattenAST(child)) {
            if (node.getScope() != previousScope) {
                continue;
            }
            // Changing node.scope does not update symbol tables
            if (node instanceof Declaration declaration && previousScope != null) {
                List<Declaration> declarations = previousScope.getSymbols().get(declaration.getSymbol());
                if (declarations != null && declarations.removeIf(candidate -> candidate == declaration)) {
                    parentScope.addSymbol(declaration.getSymbol(), declaration);
                }
            }
            node.setScope(parentScope);
            Scope nestedScope = ctx.getScopeManager().lookupScope(node);
            if (nestedScope != null) {
                changeScopeParent(nestedScope, parentScope);
            }
        }
    }

    private static void changeScopeParent(Scope scope, Scope parent) {
        if (scope.getParent() == parent) {
            return;
        }
        // CPG's parent setter does not maintain child lists, so both sides of the relationship must be updated
        if (scope.getParent() != null) {
            scope.getParent().getChildren().removeIf(candidate -> candidate == scope);
        }
        scope.setParent(parent);
        parent.getChildren().add(scope);
    }

    /**
     * Gets the {@link SubgraphWalker.Border} of the given node's sub-AST that links to outer nodes via EOG edges.
     * @param astRoot the root of the sub-AST
     * @return the EOG {@link SubgraphWalker.Border} of the AST
     */
    public static SubgraphWalker.Border getEogBorders(Node astRoot) {
        SubgraphWalker.Border result;
        if (astRoot instanceof Block block && !block.getStatements().isEmpty() && block.getNextEOG().isEmpty() && block.getPrevEOG().isEmpty()) {
            result = new SubgraphWalker.Border();
            SubgraphWalker.Border firstStatementBorder = getEogBorders(block.get(0));
            result.setEntries(firstStatementBorder.getEntries());
            SubgraphWalker.Border lastStatementBorder = getEogBorders(block.get(block.getStatements().size() - 1));
            result.setExits(lastStatementBorder.getExits());
        } else {
            result = SubgraphWalker.INSTANCE.getEOGPathEdges(astRoot);
            if (result.getEntries().isEmpty()) {
                List<Node> entries = worklist(List.of(astRoot), Node::getPrevEOG);
                result.setEntries(entries);
            }
            if (result.getExits().isEmpty()) {
                List<Node> exits = worklist(List.of(astRoot), Node::getNextEOG);
                result.setExits(exits);
            }

        }

        checkBorder(astRoot, result);

        return result;
    }

    private static void checkBorder(Node astRoot, SubgraphWalker.Border result) {
        if (result.getEntries().isEmpty()) {
            logger.debug("AST subtree of {} has no EOG entry", astRoot);
        } else if (result.getEntries().size() > 1) {
            logger.debug("AST subtree of {} has multiple EOG entries", astRoot);
        }
    }

    static void checkReplacementExits(Node original, Node replacement, GraphMutationContext mutationContext) {
        List<Node> exits = getEogBorders(replacement).getExits();
        // Distinct replacement exits need a mapping; broadcasting would invent paths between branches.
        if (exits.size() > 1 && getExitEdges(original, getEogBorders(original).getExits()).stream()
                .filter(edge -> edge.getEnd() != mutationContext.getDummyNeighbor())
                .anyMatch(edge -> exits.stream().noneMatch(exit -> exit == edge.getStart()))) {
            throw new TransformationException("Replacing multiple EOG exits requires an explicit exit mapping");
        }
    }

    static void transferEogSuccessor(Node original, Node replacement, GraphMutationContext mutationContext) {
        checkReplacementExits(original, replacement, mutationContext);
        List<Node> replacementExits = getEogBorders(replacement).getExits();

        List<DetachedEdge> outgoing = disconnectSuccessors(original, getEogBorders(original).getExits(), mutationContext);
        disconnectSuccessors(replacement, replacementExits, mutationContext);

        outgoing.forEach(edge -> edge.clear(mutationContext));
        replacementExits.forEach(exit -> clearDummyNeighbors(exit, mutationContext));

        if (replacementExits.isEmpty()) {
            return;
        }

        for (DetachedEdge edge : outgoing) {
            PropertyEdge<Node> connection = edge.original();
            if (replacementExits.size() == 1) {
                connection.setStart(replacementExits.getFirst());
            }
            connectEogEdge(connection, mutationContext);
        }

    }

    static void transferEogPredecessor(Node original, Node replacement, GraphMutationContext mutationContext) {
        Node originalEntry = getEntry(original);
        Node replacementEntry = getEntry(replacement);
        // A moved node must be replaced at its current location, not at a saved earlier boundary.
        List<DetachedEdge> incoming = disconnectPredecessors(original, originalEntry, mutationContext);
        disconnectPredecessors(replacement, replacementEntry, mutationContext);
        // Clearing during each reconnection would erase earlier join predecessors.
        getEntryEdges(replacement, replacementEntry).forEach(TransformationUtil::removeEogEdge);

        for (DetachedEdge edge : incoming) {
            retargetEogEdge(edge.outgoing(), replacementEntry);
            edge.clear(mutationContext);
        }
    }

    /**
     * Changes an EOG edge's target while maintaining reciprocal adjacency.
     * @param edge the attached EOG edge
     * @param target the new target
     */
    public static void retargetEogEdge(PropertyEdge<Node> edge, Node target) {
        edge.getEnd().getPrevEOGEdges().removeIf(candidate -> candidate == edge);
        edge.setEnd(target);
        target.addPrevEOG(edge);
    }

    private static List<DetachedEdge> disconnectSuccessors(Node node, Collection<? extends Node> exits, GraphMutationContext mutationContext) {
        // Each operation captures its current boundary; replaying saved edges can reconnect an earlier location.
        return detachEdges(getExitEdges(node, exits), mutationContext);
    }

    /* package-private */ static List<DetachedEdge> disconnectPredecessors(Node node, Node entry, GraphMutationContext mutationContext) {
        return detachEdges(getEntryEdges(node, entry), mutationContext);
    }

    private static List<DetachedEdge> detachEdges(Collection<? extends PropertyEdge<Node>> edges, GraphMutationContext mutationContext) {
        var dummy = mutationContext.getDummyNeighbor();
        return edges.stream().filter(edge -> edge.getStart() != dummy && edge.getEnd() != dummy)
                .map(edge -> DetachedEdge.detach(edge, mutationContext)).toList();
    }

    private record DetachedEdge(PropertyEdge<Node> original, PropertyEdge<Node> outgoing, PropertyEdge<Node> incoming) {
        private static DetachedEdge detach(PropertyEdge<Node> edge, GraphMutationContext mutationContext) {
            var dummy = mutationContext.getDummyNeighbor();
            PropertyEdge<Node> original = new PropertyEdge<>(edge);
            Node successor = edge.getEnd();
            PropertyEdge<Node> incoming = new PropertyEdge<>(edge);

            edge.setEnd(dummy);
            mutationContext.registerIncomingEdge(edge);

            incoming.setStart(dummy);
            mutationContext.registerOutgoingEdge(incoming);
            // Fan-out edges belong to different incoming lists; preserve the other join predecessors.
            int index = successor.getPrevEOGEdges().indexOf(edge);
            successor.getPrevEOGEdges().set(index, incoming);
            return new DetachedEdge(original, edge, incoming);
        }

        private void clear(GraphMutationContext mutationContext) {
            var dummy = mutationContext.getDummyNeighbor();

            // Repeated cuts can have identical endpoints, so consume the exact halves, not an endpoint lookup.
            if (outgoing.getEnd() == dummy) {
                removeEogEdge(outgoing);
            }

            if (incoming.getStart() == dummy) {
                removeEogEdge(incoming);
            }
        }
    }

    private static void clearDummyNeighbors(Node node, GraphMutationContext mutationContext) {
        var dummy = mutationContext.getDummyNeighbor();

        // A moved subtree is reattached here; keep the opposite halves for its old context's reconnection.
        for (PropertyEdge<Node> edge : List.copyOf(node.getPrevEOGEdges())) {
            if (edge.getStart() == dummy) {
                removeEogEdge(edge);
            }
        }

        for (PropertyEdge<Node> edge : List.copyOf(node.getNextEOGEdges())) {
            if (edge.getEnd() == dummy) {
                removeEogEdge(edge);
            }
        }
    }

    private static PropertyEdge<Node> combineEogEdges(PropertyEdge<Node> incoming, PropertyEdge<Node> outgoing) {
        PropertyEdge<Node> connection = new PropertyEdge<>(incoming);
        connection.setEnd(outgoing.getEnd());

        Object incomingUnreachable = incoming.getProperty(Properties.UNREACHABLE);
        Object outgoingUnreachable = outgoing.getProperty(Properties.UNREACHABLE);
        if (incomingUnreachable != null || outgoingUnreachable != null) {
            connection.addProperty(Properties.UNREACHABLE, Boolean.TRUE.equals(incomingUnreachable) || Boolean.TRUE.equals(outgoingUnreachable));
        }

        return connection;
    }

    /**
     * Disconnects the node from its surrounding EOG and connects each external predecessor directly to each external
     * successor.
     * @param node the subtree to remove from the evaluation flow
     * @param mutationContext graph mutation state owned by this translation
     */
    static void removeFromEog(Node node, GraphMutationContext mutationContext) {
        // First detach the incoming and outgoing edges for the node:
        List<DetachedEdge> incoming = disconnectPredecessors(node, getEntry(node), mutationContext);
        List<DetachedEdge> outgoing = disconnectSuccessors(node, getEogBorders(node).getExits(), mutationContext);

        // Combine every incoming edge with every outgoing edge to form new edges:
        for (DetachedEdge incomingEdge : incoming) {
            for (DetachedEdge outgoingEdge : outgoing) {
                PropertyEdge<Node> connection = combineEogEdges(incomingEdge.original(), outgoingEdge.original());
                connectEogEdge(connection, mutationContext);
            }
        }

        for (DetachedEdge edge : incoming) {
            edge.clear(mutationContext);
        }

        for (DetachedEdge edge : outgoing) {
            edge.clear(mutationContext);
        }
    }

    /**
     * Gets the first EOG entry {@link Node} of the given {@link Node}.
     * @param node the node
     * @return the EOG entry node
     */
    public static Node getEntry(Node node) {
        return getEogBorders(node).getEntries().getFirst();
    }

    /**
     * Gets the EOG entry edges to the AST subtree represented by astParent, that connect to the given entry node.
     * @param astParent the root of the AST subtree
     * @param entry the EOG entry to the astParent
     * @return the entry edges
     */
    @NotNull
    public static List<PropertyEdge<Node>> getEntryEdges(Node astParent, Node entry) {
        List<Node> astNodes = SubgraphWalker.INSTANCE.flattenAST(astParent);
        return entry.getPrevEOGEdges().stream().filter(edge -> !astNodes.contains(edge.getStart())).toList();
    }

    /**
     * Gets the EOG exit edges to the AST subtree represented by astParent, that connect to the given exit node.
     * @param astParent the root of the AST subtree
     * @param exits the EOG exit nodes of the astParent
     * @return the exit edges
     */
    @NotNull
    public static List<PropertyEdge<Node>> getExitEdges(Node astParent, Collection<? extends Node> exits) {
        List<Node> astNodes = SubgraphWalker.INSTANCE.flattenAST(astParent);
        return exits.stream().flatMap(exit -> exit.getNextEOGEdges().stream())
                .filter(edge -> !astNodes.contains(edge.getEnd()) || astParent instanceof Block && edge.getEnd() == astParent).toList();

    }

    /**
     * Checks whether a node represents a throw, which CPG models as a unary operator.
     * @param node the node to check
     * @return whether the node is a throw
     */
    public static boolean isThrow(Node node) {
        return node instanceof UnaryOperator unaryOperator && "throw".equals(unaryOperator.getOperatorCode());
    }

    private static List<Node> normalExits(Node node) {
        return getEogBorders(node).getExits().stream().filter(exit -> !(exit instanceof ReturnStatement || exit instanceof BreakStatement
                || exit instanceof ContinueStatement || exit instanceof GotoStatement || isThrow(exit))).toList();
    }

    /**
     * Inserts the given {@link Node} before the given newSuccessor {@link Node} in the EOG graph.
     * @param target the node
     * @param newSuccessor the new successor node
     * @param mutationContext graph mutation state owned by this translation
     */
    public static void insertBefore(Node target, Node newSuccessor, GraphMutationContext mutationContext) {
        Node entry = getEntry(target);
        List<Node> exits = normalExits(target);
        // Detach the node from its current location
        detachSubtree(target, entry, exits, mutationContext);

        Node successorEntry = getEntry(newSuccessor);
        // Detach the incoming connections to `newSuccessor`
        List<DetachedEdge> incoming = disconnectPredecessors(newSuccessor, successorEntry, mutationContext);
        // Insert the entry into those previously detached connections
        connectInsertion(entry, exits, incoming, successorEntry, mutationContext);
    }

    /**
     * Inserts the given {@link Node} after the given newPredecessor {@link Node} in the EOG graph.
     * @param target the node
     * @param newPredecessor the new predecessor node
     * @param mutationContext graph mutation state owned by this translation
     */
    public static void insertAfter(Node target, Node newPredecessor, GraphMutationContext mutationContext) {
        Node successor = getCommonSuccessor(newPredecessor, mutationContext);
        Node entry = getEntry(target);
        if (successor == entry) {
            return;
        }
        List<Node> exits = normalExits(target);

        // Detach the node from its current location
        detachSubtree(target, entry, exits, mutationContext);

        // Detach the outgoing connections to `newPredecessor`
        List<DetachedEdge> outgoing = disconnectSuccessors(newPredecessor, normalExits(newPredecessor), mutationContext);
        // Insert the entry into those previously detached connections
        connectInsertion(entry, exits, outgoing, successor, mutationContext);
    }

    private static void detachSubtree(Node target, Node entry, Collection<? extends Node> exits, GraphMutationContext mutationContext) {
        disconnectPredecessors(target, entry, mutationContext);
        disconnectSuccessors(target, exits, mutationContext);
    }

    /**
     * Connects the inserted subtree at the detached boundary and clears temporary connections.
     * @param entry the subtree entry
     * @param exits the subtree's normal exits
     * @param boundary the detached insertion boundary
     * @param successor the following node, or {@code null}
     * @param mutationContext graph mutation state owned by this translation
     */
    private static void connectInsertion(Node entry, List<? extends Node> exits, List<DetachedEdge> boundary, Node successor,
            GraphMutationContext mutationContext) {
        for (DetachedEdge detached : boundary) {
            PropertyEdge<Node> connection = new PropertyEdge<>(detached.original());
            connection.setEnd(entry);
            connectEogEdge(connection, mutationContext);
        }

        if (successor != null) {
            for (Node exit : exits) {
                connectEogEdge(new PropertyEdge<>(exit, successor), mutationContext);
            }
        }

        for (DetachedEdge edge : boundary) {
            edge.clear(mutationContext);
        }

        clearDummyNeighbors(entry, mutationContext);
        for (Node exit : exits) {
            clearDummyNeighbors(exit, mutationContext);
        }
    }

    private static void connectEogEdge(PropertyEdge<Node> edge, GraphMutationContext mutationContext) {
        Node start = edge.getStart();
        if (start == mutationContext.getDummyNeighbor() || isThrow(start)) {
            return;
        }

        start.addNextEOG(edge);
        edge.getEnd().addPrevEOG(edge);
    }

    public static void removeEogEdge(PropertyEdge<Node> edge) {
        edge.getStart().getNextEOGEdges().removeIf(candidate -> candidate == edge);
        edge.getEnd().getPrevEOGEdges().removeIf(candidate -> candidate == edge);
    }

    /**
     * Gets the common successor of the given {@link Node} if it has one.
     * <p>
     * When all outgoing exit edges go to the same node, that node is their common successor.
     * @param predecessor the subtree whose outgoing EOG edges are examined
     * @param mutationContext graph mutation state owned by this translation
     * @return the common successor, or {@code null} if there are no outgoing edges after filtering
     * @throws TransformationException if the outgoing edges lead to distinct successor nodes
     */
    static Node getCommonSuccessor(Node predecessor, GraphMutationContext mutationContext) {
        List<PropertyEdge<Node>> successors = getExitEdges(predecessor, normalExits(predecessor)).stream()
                .filter(edge -> edge.getEnd() != mutationContext.getDummyNeighbor()).toList();
        if (successors.isEmpty()) {
            return null;
        }
        Node successor = successors.getFirst().getEnd();
        // Merging distinct successors through one inserted subtree would lose their branch association.
        //
        // Consider the following evaluation order graph:
        //
        // beta -true--> X
        // beta -false-> Y
        //
        // There are two possible destinations depending on what the result was.
        //
        // Now suppose we want to insert a node after beta while preserving both branch destinations.
        //
        // beta -> extra -> ?
        //
        // It is not obvious how to do this, for example one could connect them like this:
        //
        // beta -true--
        // |
        // |-> extra -> X or Y?
        // |
        // beta -false-
        //
        // When connecting extra to both X or Y, one would introduce paths that were not present before.
        //
        // Another solution may be to duplicate extra like this:
        //
        // beta -true--> extra -> X
        // beta -false-> extra -> Y
        //
        // This would result in the correct destinations, but duplicating extra becomes an issue.
        // The node may be the root of a subtree;
        // - one would have to clone the entire tree, not only the root
        // - adjust references (the subtree may introduce a variable which can have references)
        // - represent both copies in the AST
        // - track provenance and source locations correctly
        //
        // This is technically feasible, but a more involved effort. At the time of writing this,
        // there seem to be no normalizations that require this.
        //
        // Therefore, this case is explicitly rejected. This ensures that with new normalizations that may
        // require this, one can revisit this topic and introduce a solution.
        if (successors.stream().anyMatch(edge -> edge.getEnd() != successor)) {
            throw new TransformationException("Insertion after distinct EOG successors requires an explicit exit mapping");
        }

        return successor;
    }

    /**
     * Returns true if the {@code maybeSuccessor} {@link Node} is the AST neighbor following the {@code element} node.
     * @param element a {@link Node} object
     * @param maybeSuccessor the potential successor {@link Node}
     * @return true if {@code maybeSuccessor} is an AST successor of {@code element}
     */
    public static boolean isAstSuccessor(Node element, Node maybeSuccessor) {
        // If maybeSuccessor is the AST successor of element, then an exit edge of element points to maybeSuccessor
        // The exit is likely to be a child node, not element itself
        List<Node> exits = getEogBorders(element).getExits();
        Node entry = getEntry(maybeSuccessor);
        List<PropertyEdge<Node>> entryEdges = getEntryEdges(maybeSuccessor, entry);

        return entryEdges.stream().anyMatch(e -> exits.contains(e.getStart()));
    }

    /**
     * Returns true if the {@code maybeSuccessor} {@link Node} is the EOG successor of the {@code exit} node.
     * @param exit a {@link Node} object
     * @param maybeSuccessor the potential successor {@link Node}
     * @return true if {@code maybeSuccessor} is the EOG successor of {@code exit}
     */
    public static boolean isEogSuccessor(Node exit, Node maybeSuccessor) {
        // Unlike isAstSuccessor, we check for edges from exit directly
        Node entry = getEntry(maybeSuccessor);
        List<PropertyEdge<Node>> entryEdges = getEntryEdges(maybeSuccessor, entry);

        return entryEdges.stream().anyMatch(e -> exit == e.getStart());
    }

    /**
     * Computes the iterative successors of a list of elements w.r.t. a successor function, collecting those elements with
     * no successors.
     * @param initialList the initial list of elements
     * @param successorFunction the successor function
     * @param <T> the element type
     * @return the list of successors with no successors
     */
    private static <T> List<T> worklist(Collection<T> initialList, Function<T, ? extends List<T>> successorFunction) {
        Collection<T> seen = new ArrayList<>(initialList);
        Deque<T> worklist = new ArrayDeque<>(initialList);
        List<T> result = new ArrayList<>();
        while (!worklist.isEmpty()) {
            T element = worklist.removeFirst();
            List<T> successors = successorFunction.apply(element);
            if (successors.isEmpty()) {
                result.add(element);
                continue;
            }

            for (T successor : successors) {
                if (!seen.contains(successor)) {
                    seen.add(successor);
                    worklist.addLast(successor);
                }
            }
        }
        return result;
    }
}
