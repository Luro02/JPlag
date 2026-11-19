package de.jplag.java_cpg.transformation.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import de.fraunhofer.aisec.cpg.graph.Name;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.edge.Properties;
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge;
import de.fraunhofer.aisec.cpg.graph.statements.IfStatement;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Reference;
import de.jplag.java_cpg.transformation.TransformationException;

class TransformationUtilTest {
    @Test
    void replacementPreservesFanOutAndUnrelatedJoinPredecessors() {
        var before = node("before");
        var original = node("original");
        var replacement = node("replacement");
        var taken = node("taken");
        var skipped = node("skipped");
        var unrelated = node("unrelated");
        connect(before, original, true);
        connect(original, taken, true);
        connect(original, skipped, false);
        var joinEdge = connect(unrelated, skipped);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.transferEogPredecessor(original, replacement, context);
            TransformationUtil.transferEogSuccessor(original, replacement, context);
        }

        assertEquals(1, before.getNextEOGEdges().size());
        assertEdge(before, replacement, true);
        assertEquals(2, replacement.getNextEOGEdges().size());
        assertEdge(replacement, taken, true);
        assertEdge(replacement, skipped, false);
        assertEquals(2, skipped.getPrevEOGEdges().size());
        assertSame(joinEdge, assertEdge(unrelated, skipped, null));
        assertDetached(original);
    }

    @Test
    void replacementRejectsAmbiguousExitMappingBeforeDisconnecting() {
        var original = branchingNode("original");
        var replacement = branchingNode("replacement");
        var taken = node("taken");
        var skipped = node("skipped");
        var discarded = node("discarded");
        var originalFirst = original.getThenStatement();
        var originalSecond = original.getElseStatement();
        connect(originalFirst, taken);
        connect(originalSecond, skipped);
        var replacementFirst = replacement.getThenStatement();
        var replacementSecond = replacement.getElseStatement();
        connect(replacementFirst, discarded);
        connect(replacementSecond, discarded);

        try (var context = new GraphMutationContext()) {
            assertThrows(TransformationException.class, () -> TransformationUtil.transferEogSuccessor(original, replacement, context));
        }

        assertEdge(originalFirst, taken, null);
        assertEdge(originalSecond, skipped, null);
        assertEdge(replacementFirst, discarded, null);
        assertEdge(replacementSecond, discarded, null);
    }

    @Test
    void promotingExistingExitsPreservesTheirSeparateContinuations() {
        var replacement = branchingNode("replacement");
        var original = new Block();
        original.setStatements(List.of(replacement));
        var taken = node("taken");
        var skipped = node("skipped");
        connect(replacement.getThenStatement(), taken);
        connect(replacement.getElseStatement(), skipped);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.transferEogSuccessor(original, replacement, context);
        }

        assertEquals(1, replacement.getThenStatement().getNextEOGEdges().size());
        assertEquals(1, replacement.getElseStatement().getNextEOGEdges().size());
        assertEdge(replacement.getThenStatement(), taken, null);
        assertEdge(replacement.getElseStatement(), skipped, null);
    }

    private static IfStatement branchingNode(String name) {
        var statement = new IfStatement();
        var condition = node(name + "Condition");
        var first = node(name + "First");
        var second = node(name + "Second");
        statement.setCondition(condition);
        statement.setThenStatement(first);
        statement.setElseStatement(second);
        connect(condition, first, true);
        connect(condition, second, false);
        return statement;
    }

    private static Reference node(String name) {
        var reference = new Reference();
        reference.setName(new Name(name, null, "."));
        return reference;
    }

    private static PropertyEdge<Node> connect(Node start, Node end) {
        var edge = new PropertyEdge<>(start, end);
        start.addNextEOG(edge);
        end.addPrevEOG(edge);
        return edge;
    }

    private static PropertyEdge<Node> connect(Node start, Node end, boolean branch) {
        var edge = connect(start, end);
        edge.addProperty(Properties.BRANCH, branch);
        return edge;
    }

    private static void assertDetached(Node... nodes) {
        for (Node node : nodes) {
            assertTrue(node.getPrevEOGEdges().isEmpty(), () -> "Unexpected incoming EOG edges at " + node.getName());
            assertTrue(node.getNextEOGEdges().isEmpty(), () -> "Unexpected outgoing EOG edges at " + node.getName());
        }
    }

    private static PropertyEdge<Node> assertEdge(Node start, Node end, Boolean branch) {
        String description = start.getName() + " -> " + end.getName() + " (branch=" + branch + ")";
        var edges = start.getNextEOGEdges().stream()
                .filter(edge -> edge.getEnd() == end && Objects.equals(branch, edge.getProperty(Properties.BRANCH))).toList();
        assertEquals(1, edges.size(), "Expected exactly one EOG edge: " + description);
        var edge = edges.getFirst();
        assertSame(start, edge.getStart(), "Incorrect EOG edge source: " + description);
        assertTrue(end.getPrevEOGEdges().stream().anyMatch(incoming -> incoming == edge), "Missing reciprocal EOG edge: " + description);
        return edge;
    }
}
