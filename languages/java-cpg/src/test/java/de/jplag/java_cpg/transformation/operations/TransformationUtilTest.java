package de.jplag.java_cpg.transformation.operations;

import static de.jplag.java_cpg.transformation.matching.edges.Edges.BLOCK__STATEMENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import de.fraunhofer.aisec.cpg.ScopeManager;
import de.fraunhofer.aisec.cpg.TranslationContext;
import de.fraunhofer.aisec.cpg.graph.Name;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.edge.Properties;
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge;
import de.fraunhofer.aisec.cpg.graph.scopes.Scope;
import de.fraunhofer.aisec.cpg.graph.statements.IfStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ReturnStatement;
import de.fraunhofer.aisec.cpg.graph.statements.Statement;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Reference;
import de.jplag.java_cpg.transformation.TransformationException;
import de.jplag.java_cpg.transformation.matching.edges.CpgNthEdge;

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
    void removalPreservesEveryPredecessorAndSuccessor() {
        var first = node("first");
        var second = node("second");
        var removed = node("removed");
        var taken = node("taken");
        var skipped = node("skipped");
        connect(first, removed, true);
        connect(second, removed);
        connect(removed, taken, true);
        connect(removed, skipped, false);
        var block = new Block();
        block.setStatements(List.of(removed));

        try (var context = new GraphMutationContext()) {
            RemoveOperation.apply(block, removed, new CpgNthEdge<>(BLOCK__STATEMENTS, 0), true, context);
        }

        assertTrue(block.getStatements().isEmpty());
        for (Node predecessor : List.of(first, second)) {
            assertEquals(2, predecessor.getNextEOGEdges().size());
            Boolean branch = predecessor == first ? true : null;
            assertEdge(predecessor, taken, branch);
            assertEdge(predecessor, skipped, branch);
        }
        assertEquals(2, taken.getPrevEOGEdges().size());
        assertEquals(2, skipped.getPrevEOGEdges().size());
        assertDetached(removed);
    }

    @Test
    void consecutiveRemovalsUseCurrentPredecessors() {
        var before = node("before");
        var first = node("first");
        var second = node("second");
        var after = node("after");
        connect(before, first, true);
        connect(first, second);
        connect(second, after);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.removeFromEog(first, context);
            TransformationUtil.removeFromEog(second, context);
        }

        assertEquals(1, before.getNextEOGEdges().size());
        assertEdge(before, after, true);
        assertDetached(first, second);
        assertEquals(1, after.getPrevEOGEdges().size());
    }

    @Test
    void removalKeepsTheSurvivingDecisionLabel() {
        var before = node("before");
        var removed = node("removed");
        var after = node("after");
        connect(before, removed, true);
        connect(removed, after, false);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.removeFromEog(removed, context);
        }

        assertEquals(1, before.getNextEOGEdges().size());
        assertEdge(before, after, true);
        assertDetached(removed);
    }

    @ParameterizedTest
    @CsvSource({"true, false, true", "false, true, true", "true, true, true", "false, false, false", ", true, true", "true, , true", ", , "})
    void removalComposesReachability(Boolean incoming, Boolean outgoing, Boolean expected) {
        var before = node("before");
        var removed = node("removed");
        var after = node("after");
        connect(before, removed).addProperty(Properties.UNREACHABLE, incoming);
        connect(removed, after).addProperty(Properties.UNREACHABLE, outgoing);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.removeFromEog(removed, context);
        }

        assertEquals(1, before.getNextEOGEdges().size());
        var edge = assertEdge(before, after, null);
        assertEquals(expected, edge.getProperty(Properties.UNREACHABLE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void insertionSplicesTheSequenceAtEveryPosition(int index) {
        var before = node("before");
        var first = node("first");
        var second = node("second");
        var after = node("after");
        var inserted = node("inserted");
        connect(before, first, true);
        connect(first, second);
        connect(second, after);
        var block = new Block();
        block.setStatements(List.of(first, second));
        var translationContext = translationContext(block);
        var operation = new InsertOperation<Block, Statement>(null, new CpgNthEdge<>(BLOCK__STATEMENTS, index), null, true);

        try (var context = new GraphMutationContext()) {
            operation.apply(translationContext, block, inserted, index, context);
        }

        var expected = new ArrayList<Node>(List.of(before, first, second, after));
        expected.add(index + 1, inserted);
        for (int position = 0; position < expected.size() - 1; position++) {
            Node start = expected.get(position);
            Node end = expected.get(position + 1);
            assertEquals(1, start.getNextEOGEdges().size());
            assertEdge(start, end, position == 0 ? true : null);
        }
        assertEquals(expected.subList(1, expected.size() - 1), block.getStatements());
        assertTrue(before.getPrevEOGEdges().isEmpty());
        assertTrue(after.getNextEOGEdges().isEmpty());
    }

    @Test
    void insertionRejectsDistinctContinuationsBeforeChangingTheGraph() {
        var before = node("before");
        var inserted = node("inserted");
        var taken = node("taken");
        var skipped = node("skipped");
        connect(before, taken, true);
        connect(before, skipped, false);
        var block = new Block();
        block.setStatements(List.of(before));
        var translationContext = translationContext(block);
        var operation = new InsertOperation<Block, Statement>(null, new CpgNthEdge<>(BLOCK__STATEMENTS, 1), null, true);

        try (var context = new GraphMutationContext()) {
            assertThrows(TransformationException.class, () -> operation.apply(translationContext, block, inserted, 1, context));
        }

        assertEquals(List.of(before), block.getStatements());
        assertEquals(2, before.getNextEOGEdges().size());
        assertEdge(before, taken, true);
        assertEdge(before, skipped, false);
        assertDetached(inserted);
    }

    @Test
    void insertionReconnectsEverySubtreeExit() {
        var predecessor = branchingNode("predecessor");
        var first = predecessor.getThenStatement();
        var second = predecessor.getElseStatement();
        var continuation = node("continuation");
        connect(first, continuation);
        connect(second, continuation);
        var inserted = branchingNode("inserted");
        var entry = inserted.getCondition();
        var insertedFirst = inserted.getThenStatement();
        var insertedSecond = inserted.getElseStatement();
        var oldPredecessor = node("oldPredecessor");
        var oldSuccessor = node("oldSuccessor");
        connect(oldPredecessor, entry);
        connect(insertedFirst, oldSuccessor);
        connect(insertedSecond, oldSuccessor);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.insertAfter(inserted, predecessor, context);
        }

        for (Node exit : List.of(first, second)) {
            assertEquals(1, exit.getNextEOGEdges().size());
            assertEdge(exit, entry, null);
        }
        for (Node exit : List.of(insertedFirst, insertedSecond)) {
            assertEquals(1, exit.getNextEOGEdges().size());
            assertEdge(exit, continuation, null);
        }
        assertTrue(oldPredecessor.getNextEOGEdges().isEmpty());
        assertTrue(oldSuccessor.getPrevEOGEdges().isEmpty());
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
    void consumingACutDoesNotConsumeAnEarlierCutWithTheSameEndpoints() {
        var before = node("before");
        var removed = node("removed");
        var after = node("after");
        connect(before, removed);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.disconnectPredecessors(removed, TransformationUtil.getEntry(removed), context);
            var pendingOutgoing = before.getNextEOGEdges().getFirst();
            var pendingIncoming = removed.getPrevEOGEdges().getFirst();
            connect(before, removed, true);
            connect(removed, after);

            TransformationUtil.removeFromEog(removed, context);

            assertTrue(before.getNextEOGEdges().stream().anyMatch(edge -> edge == pendingOutgoing));
            assertTrue(removed.getPrevEOGEdges().stream().anyMatch(edge -> edge == pendingIncoming));
            assertEdge(before, after, true);
        }

        assertEquals(1, before.getNextEOGEdges().size());
        assertTrue(removed.getPrevEOGEdges().isEmpty());
    }

    @Test
    void insertingAnAlreadyAdjacentNodeDoesNotChangeTheSequence() {
        var before = node("before");
        var inserted = node("inserted");
        var after = node("after");
        var prefix = connect(before, inserted);
        var suffix = connect(inserted, after);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.insertAfter(inserted, before, context);
        }

        assertSame(prefix, assertEdge(before, inserted, null));
        assertSame(suffix, assertEdge(inserted, after, null));
        assertEquals(1, inserted.getPrevEOGEdges().size());
        assertEquals(1, inserted.getNextEOGEdges().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void insertingAReturnDoesNotCreateFallThrough(boolean append) {
        var before = node("before");
        var after = node("after");
        var inserted = new ReturnStatement();
        connect(before, after);

        try (var context = new GraphMutationContext()) {
            if (append) {
                TransformationUtil.insertAfter(inserted, before, context);
            } else {
                TransformationUtil.insertBefore(inserted, after, context);
            }
        }

        assertEdge(before, inserted, null);
        assertTrue(inserted.getNextEOGEdges().isEmpty());
        assertTrue(after.getPrevEOGEdges().isEmpty());
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

    @Test
    void insertionPreservesAllIncomingBranches() {
        var first = node("first");
        var second = node("second");
        var inserted = node("inserted");
        var successor = node("successor");
        connect(first, successor, true);
        connect(second, successor, false);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.insertBefore(inserted, successor, context);
        }

        assertEdge(first, inserted, true);
        assertEdge(second, inserted, false);
        assertEquals(2, inserted.getPrevEOGEdges().size());
        assertEquals(1, inserted.getNextEOGEdges().size());
        assertEdge(inserted, successor, null);
        assertEquals(1, successor.getPrevEOGEdges().size());
    }

    @Test
    void movingAStatementRetainsPlaceholdersForDfgReconnection() {
        var before = node("before");
        var moved = node("moved");
        var after = node("after");
        var destinationPredecessor = node("destinationPredecessor");
        var destination = node("destination");
        connect(before, moved);
        connect(moved, after);
        connect(destinationPredecessor, destination);

        try (var context = new GraphMutationContext()) {
            TransformationUtil.insertBefore(moved, destination, context);

            assertEdge(before, context.getDummyNeighbor(), null);
            assertEdge(context.getDummyNeighbor(), after, null);
            assertEdge(destinationPredecessor, moved, null);
            assertEdge(moved, destination, null);
            assertEquals(1, moved.getPrevEOGEdges().size());
            assertEquals(1, moved.getNextEOGEdges().size());
        }
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

    private static TranslationContext translationContext(Block block) {
        var context = mock(TranslationContext.class);
        var scopeManager = mock(ScopeManager.class);
        var scope = mock(Scope.class);
        when(context.getScopeManager()).thenReturn(scopeManager);
        when(scopeManager.lookupScope(block)).thenReturn(scope);
        return context;
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
