package de.jplag.java_cpg.visitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
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
import de.fraunhofer.aisec.cpg.graph.statements.CatchClause;
import de.fraunhofer.aisec.cpg.graph.statements.DoStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ForEachStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ForStatement;
import de.fraunhofer.aisec.cpg.graph.statements.IfStatement;
import de.fraunhofer.aisec.cpg.graph.statements.Statement;
import de.fraunhofer.aisec.cpg.graph.statements.SwitchStatement;
import de.fraunhofer.aisec.cpg.graph.statements.SynchronizedStatement;
import de.fraunhofer.aisec.cpg.graph.statements.TryStatement;
import de.fraunhofer.aisec.cpg.graph.statements.WhileStatement;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.CallExpression;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker;
import de.fraunhofer.aisec.cpg.processing.IStrategy;
import de.fraunhofer.aisec.cpg.processing.strategy.Strategy;

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
            case MethodDeclaration methodDecl -> Iterators.concat(methodDecl.getParameters().iterator(),
                    Stream.<Node>ofNullable(methodDecl.getBody()).iterator());
            case WhileStatement whileStatement -> children(whileStatement.getConditionDeclaration(), whileStatement.getCondition(),
                    whileStatement.getStatement());
            case DoStatement doStatement -> children(doStatement.getStatement(), doStatement.getCondition());
            case ForStatement forStatement -> children(forStatement.getInitializerStatement(), forStatement.getConditionDeclaration(),
                    forStatement.getCondition(), forStatement.getIterationStatement(), forStatement.getStatement());
            case ForEachStatement forEachStatement -> children(forEachStatement.getVariable(), forEachStatement.getIterable(),
                    forEachStatement.getStatement());
            case IfStatement ifStatement -> children(ifStatement.getInitializerStatement(), ifStatement.getConditionDeclaration(),
                    ifStatement.getCondition(), ifStatement.getThenStatement(), ifStatement.getElseStatement());
            case SwitchStatement switchStatement -> children(switchStatement.getInitializerStatement(), switchStatement.getSelectorDeclaration(),
                    switchStatement.getSelector(), switchStatement.getStatement());
            case SynchronizedStatement synchronizedStatement -> children(synchronizedStatement.getExpression(), synchronizedStatement.getBlock());
            case TryStatement tryStatement -> Iterators.concat(tryStatement.getResources().iterator(), children(tryStatement.getTryBlock()),
                    tryStatement.getCatchClauses().iterator(), children(tryStatement.getFinallyBlock()));
            case CatchClause catchClause -> children(catchClause.getParameter(), catchClause.getBody());
            case Block block -> block.getStatements().stream().<Node>map(statement -> statement).iterator();
            case CallExpression call -> Iterators.concat(children(call.getCallee()), call.getArguments().iterator());
            default -> Strategy.INSTANCE.AST_FORWARD(node);
        };
    }

    private static Iterator<Node> children(Node... nodes) {
        return Stream.of(nodes).filter(Objects::nonNull).iterator();
    }

    /**
     * Traverses structural children, visiting calls after their receiver and arguments.
     * @param root the traversal root
     * @param onVisit the node visitor
     * @param onExit the node exit handler
     */
    public void walk(Node root, Consumer<? super Node> onVisit, Consumer<? super Node> onExit) {
        Deque<Node> stack = new ArrayDeque<>();
        Consumer<Node> exit = node -> {
            if (node instanceof CallExpression) {
                onVisit.accept(node);
            }
            onExit.accept(node);
        };
        SubgraphWalker.IterativeGraphWalker walker = new SubgraphWalker.IterativeGraphWalker();
        walker.setStrategy(this::getIterator);
        walker.registerOnNodeVisit((node, parent) -> {
            while (!stack.isEmpty() && stack.peek() != parent) {
                exit.accept(stack.pop());
            }
            if (!(node instanceof CallExpression)) {
                onVisit.accept(node);
            }
            stack.push(node);
            return Unit.INSTANCE;
        });
        walker.iterate(root);
        while (!stack.isEmpty()) {
            exit.accept(stack.pop());
        }
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
        List<Node> nodes = new ArrayList<>();
        new NodeOrderStrategy().walk(statement, nodes::add, node -> {
        });
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
