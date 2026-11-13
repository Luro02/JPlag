package de.jplag.java_cpg.visitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

import de.fraunhofer.aisec.cpg.TranslationResult;
import de.fraunhofer.aisec.cpg.graph.Component;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.declarations.EnumDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.FunctionDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.MethodDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.NamespaceDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.RecordDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.TranslationUnitDeclaration;
import de.fraunhofer.aisec.cpg.graph.scopes.ValueDeclarationScope;
import de.fraunhofer.aisec.cpg.graph.statements.DoStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ForEachStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ForStatement;
import de.fraunhofer.aisec.cpg.graph.statements.IfStatement;
import de.fraunhofer.aisec.cpg.graph.statements.Statement;
import de.fraunhofer.aisec.cpg.graph.statements.WhileStatement;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker;
import de.fraunhofer.aisec.cpg.processing.IStrategy;
import de.fraunhofer.aisec.cpg.processing.strategy.Strategy;

import de.jplag.java_cpg.transformation.operations.TransformationUtil;

import com.google.common.collect.Iterators;
import kotlin.Unit;

/**
 * This class defines the order of visitation of the CPG {@link Node}s.
 */
public class NodeOrderStrategy implements IStrategy<Node> {

    private static final boolean USE_CALL_GRAPH_ORDER = true;
    private List<MethodDeclaration> methodOrder;

    /**
     * Creates a new {@link NodeOrderStrategy}.
     */
    public NodeOrderStrategy() {
        // nothing to do yet
    }

    @Override
    @NotNull
    public Iterator<Node> getIterator(Node node) {
        return switch (node) {
            case TranslationResult translationResult -> {
                this.methodOrder = new MethodOrderStrategy().setupMethodCallGraphOrder(translationResult);
                yield Strategy.INSTANCE.AST_FORWARD(node);
            }
            case Component c -> walkComponent(c);
            case TranslationUnitDeclaration tu -> walkTranslationUnit(tu);
            case RecordDeclaration recordDecl -> walkRecord(recordDecl);
            case MethodDeclaration methodDecl -> Iterators.concat(
                methodDecl.getParameters().iterator(),
                Stream.<Node>ofNullable(methodDecl.getBody()).iterator());
            // Condition is visited already at this point
            case WhileStatement whileStatement -> optionalNode(whileStatement.getStatement());
            // Condition is visited already at this point
            case DoStatement doStatement -> optionalNode(doStatement.getStatement());
            // Condition is visited already at this point
            case ForStatement forStatement -> optionalNode(forStatement.getStatement());
            // Condition is visited already at this point
            case ForEachStatement forEachStatement -> optionalNode(forEachStatement.getStatement());
            // Condition is already visited at this point
            case IfStatement ifStatement ->
                Stream.<Node>of(ifStatement.getThenStatement(), ifStatement.getElseStatement()).filter(Objects::nonNull).iterator();
            case Block block -> block.getStatements().stream().map(TransformationUtil::getEntry).iterator();
            case Node nodeT when nodeT.getScope() instanceof ValueDeclarationScope -> Strategy.INSTANCE.EOG_FORWARD(node);
            default -> Strategy.INSTANCE.AST_FORWARD(node);
        };
    }

    private static Iterator<Node> optionalNode(Statement statement) {
        return Stream.<Node>ofNullable(statement).iterator();
    }

    private static Iterator<Node> walkTranslationUnit(TranslationUnitDeclaration tu) {
        return getTopLevelRecords(tu).stream().<Node>map(record -> record).iterator();
    }

    private static Iterator<Node> walkComponent(Component component) {
        var files = component.getTranslationUnits().stream()
                .collect(Collectors.partitioningBy(tu -> getTopLevelRecords(tu).stream().anyMatch(NodeOrderStrategy::isMainClass)));

        return Iterators.concat(files.get(true).iterator(), files.get(false).iterator());
    }

    private static boolean isMainClass(RecordDeclaration recordDeclaration) {
        return recordDeclaration.getMethods().stream().anyMatch(NodeOrderStrategy::isMainMethod);
    }

    private static List<RecordDeclaration> getTopLevelRecords(Node node) {
        List<RecordDeclaration> result = new ArrayList<>();
        Deque<Node> declarations = new ArrayDeque<>(List.of(node));
        while (!declarations.isEmpty()) {
            Node declaration = declarations.removeFirst();
            Collection<? extends Node> children = switch (declaration) {
                case Component component -> component.getTranslationUnits();
                case TranslationUnitDeclaration tu -> tu.getDeclarations();
                case NamespaceDeclaration namespace -> namespace.getDeclarations();
                case RecordDeclaration record -> {
                    result.add(record);
                    yield List.of();
                }
                default -> List.of();
            };

            for (Node child : children) {
                if (child != null) {
                    declarations.addLast(child);
                }
            }
        }
        return result;
    }

    private Iterator<Node> walkRecord(RecordDeclaration recordDecl) {
        var methods = Stream.concat(recordDecl.getConstructors().stream(), recordDecl.getMethods().stream()).filter(MethodDeclaration::hasBody)
                .sorted(methodComparator()).iterator();

        return Iterators.concat(recordDecl instanceof EnumDeclaration enumDecl ? enumDecl.getEntries().iterator() : Collections.emptyIterator(),
                recordDecl.getFields().iterator(), methods, recordDecl.getTemplates().iterator(), recordDecl.getRecords().iterator());
    }

    static boolean isMainMethod(FunctionDeclaration function) {
        return function instanceof MethodDeclaration method && method.isStatic() && method.getName().getLocalName().equals("main")
                && method.getReturnTypes().size() == 1 && method.getReturnTypes().getFirst().getTypeName().equals("void");
    }

    /**
     * Finds all child {@link Node}s of the given {@link Statement} in the order determined by the
     * {@link NodeOrderStrategy}.
     * @param statement the statement
     * @return a list of all child nodes
     */
    public static List<Node> flattenStatement(Statement statement) {
        Set<Node> astChildren = new HashSet<>(SubgraphWalker.INSTANCE.flattenAST(statement));
        IStrategy<Node> strategy = new NodeOrderStrategy();
        Node entry = TransformationUtil.getEntry(statement);

        List<Node> nodes = new ArrayList<>(astChildren.size());
        SubgraphWalker.IterativeGraphWalker walker = new SubgraphWalker.IterativeGraphWalker();
        walker.setStrategy(node -> Iterators.filter(strategy.getIterator(node), astChildren::contains));
        walker.registerOnNodeVisit((node, _) -> {
            nodes.add(node);
            return Unit.INSTANCE;
        });
        walker.iterate(entry);
        return nodes;
    }

    private Comparator<MethodDeclaration> methodComparator() {
        Comparator<MethodDeclaration> sourceOrder = Comparator.comparing(
                method -> method.getLocation() == null ? null : method.getLocation().getRegion(), Comparator.nullsLast(Comparator.naturalOrder()));
        if (!USE_CALL_GRAPH_ORDER || methodOrder == null) {
            return sourceOrder;
        }

        return Comparator.<MethodDeclaration>comparingInt(method -> {
            int index = methodOrder.indexOf(method);
            return index < 0 ? Integer.MAX_VALUE : index;
        }).thenComparing(sourceOrder);
    }
}
