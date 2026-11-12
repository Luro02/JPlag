package de.jplag.java_cpg.token;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.processing.IVisitable;
import de.fraunhofer.aisec.cpg.processing.IVisitor;

/**
 * This class adds exit methods to the {@link IVisitor} class. Unlike {@link IVisitor}, it propagates handler failures.
 * @param <V> the object type to visit and exit
 */
public abstract class VisitorExitor<V extends IVisitable<V>> extends IVisitor<V> {

    private static final String EXIT_METHOD_IDENTIFIER = "exit";

    @Override
    public void visit(V node) {
        invokeHandler("visit", node);
    }

    /**
     * Calls the most specific implementation of exit for the given {@link Node}.
     * @param node the node
     */
    public void exit(V node) {
        invokeHandler(EXIT_METHOD_IDENTIFIER, node);
    }

    private void invokeHandler(String name, V node) {
        try {
            Method handler = this.getClass().getMethod(name, node.getClass());
            handler.invoke(this, node);
        } catch (NoSuchMethodException e) {
            // Do nothing, just like in IVisitor
        } catch (InvocationTargetException exception) {
            switch (exception.getCause()) {
                case RuntimeException cause -> throw cause;
                case Error cause -> throw cause;
                default -> throw new IllegalStateException("Token handler failed for " + node.getClass().getSimpleName(), exception.getCause());
            }
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access token handler for " + node.getClass().getSimpleName(), exception);
        }
    }

}
