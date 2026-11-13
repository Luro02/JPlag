package de.jplag.java_cpg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.fraunhofer.aisec.cpg.TranslationConfiguration;
import de.fraunhofer.aisec.cpg.TranslationManager;
import de.fraunhofer.aisec.cpg.frontends.java.JavaLanguage;
import de.fraunhofer.aisec.cpg.graph.Name;
import de.fraunhofer.aisec.cpg.graph.declarations.MethodDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.RecordDeclaration;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker;
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation;
import de.fraunhofer.aisec.cpg.sarif.Region;
import de.jplag.java_cpg.visitor.NodeOrderStrategy;

/**
 * Checks method ordering with and without a translation's call-graph order.
 */
class NodeOrderStrategyTest {
    private static final Path FILE = Path.of("src", "test", "resources", "java", "GetterSetter.java").toAbsolutePath();

    @Test
    void sourceOrderPlacesMissingLocationsLast() {
        var record = new RecordDeclaration();
        record.setMethods(List.of(method("later", 11), method("implicit", 0), method("earlier", 7)));

        assertEquals(List.of("earlier", "later", "implicit"), methodNames(new NodeOrderStrategy(), record));
    }

    @Test
    void missingLocationsKeepInsertionOrder() {
        var record = new RecordDeclaration();
        record.setMethods(List.of(method("first", 0), method("second", 0)));

        assertEquals(List.of("first", "second"), methodNames(new NodeOrderStrategy(), record));
    }

    /**
     * Source positions may change after indexing; unranked methods must not precede ranked methods.
     */
    @Test
    void callGraphOrderTakesPrecedenceOverSourceOrder() throws Exception {
        var configuration = new TranslationConfiguration.Builder().sourceLocations(FILE.toFile()).registerLanguage(new JavaLanguage()).build();
        var graph = TranslationManager.builder().config(configuration).build().analyze().get();
        var record = SubgraphWalker.INSTANCE.flattenAST(graph).stream().filter(RecordDeclaration.class::isInstance).map(RecordDeclaration.class::cast)
                .filter(candidate -> candidate.getName().getLocalName().equals("GetterSetter")).findFirst().orElseThrow();
        var strategy = new NodeOrderStrategy();
        strategy.getIterator(graph);

        MethodDeclaration getter = record.getMethods().getFirst();
        MethodDeclaration setter = record.getMethods().getLast();
        PhysicalLocation getterLocation = getter.getLocation();
        getter.setLocation(setter.getLocation());
        setter.setLocation(getterLocation);
        record.addMethod(method("unrankedLater", 2));
        record.addMethod(method("unrankedEarlier", 1));

        assertEquals(List.of("getInstance", "setInstance", "unrankedEarlier", "unrankedLater"), methodNames(strategy, record));
    }

    private static MethodDeclaration method(String name, int line) {
        var method = new MethodDeclaration();
        method.setName(new Name(name, null, "."));
        method.setBody(new Block());
        if (line > 0) {
            method.setLocation(new PhysicalLocation(FILE.toUri(), new Region(line, 1, line, 2)));
        }
        return method;
    }

    private static List<String> methodNames(NodeOrderStrategy strategy, RecordDeclaration record) {
        List<String> names = new ArrayList<>();
        strategy.getIterator(record).forEachRemaining(node -> {
            if (node instanceof MethodDeclaration method) {
                names.add(method.getName().getLocalName());
            }
        });
        return names;
    }
}
