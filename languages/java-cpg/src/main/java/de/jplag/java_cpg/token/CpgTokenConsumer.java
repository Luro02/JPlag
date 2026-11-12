package de.jplag.java_cpg.token;

import java.io.File;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.aisec.cpg.graph.Name;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.declarations.Declaration;
import de.fraunhofer.aisec.cpg.graph.declarations.RecordDeclaration;
import de.fraunhofer.aisec.cpg.graph.statements.CatchClause;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.AssignExpression;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.CallExpression;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.LambdaExpression;
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation;
import de.fraunhofer.aisec.cpg.sarif.Region;
import de.jplag.Token;
import de.jplag.TokenType;

/**
 * A {@link CpgTokenConsumer} can create appropriate {@link CpgToken}s for {@link TokenType}s.
 */
public abstract class CpgTokenConsumer implements TokenConsumer {

    private static final Logger logger = LoggerFactory.getLogger(CpgTokenConsumer.class);
    /**
     * This is used as the Token's length if the token spans multiple lines. The value is supposed to be greater than the
     * length of any sensible line of code.
     */
    private static final int MULTILINE_TOKEN_LENGTH = 1024;

    private static int calculateLength(Region region) {
        if (region.getEndLine() == region.startLine) {
            // CPG regions use exclusive end columns, just like JPlag tokens.
            return region.getEndColumn() - region.startColumn;
        } else
            return MULTILINE_TOKEN_LENGTH;
    }

    /**
     * Adds a new {@link Token} for the given {@link TokenType} and {@link Node}.
     * @param type the {@link TokenType}
     * @param node the represented {@link Node}
     * @param isEndToken true iff the token represents the end of a block
     */
    public void addToken(TokenType type, Node node, boolean isEndToken) {
        logger.debug("{} / {}", type, node);
        PhysicalLocation location = node.getLocation();

        if (Objects.isNull(location)) {
            if (node instanceof Declaration) {
                // implicit node, e.g., declaration of API classes - we do not want tokens for those.
                return;
            }

            throw new IllegalStateException(
                    "Cannot create %s token for %s without a source location.".formatted(type, node.getClass().getSimpleName()));
        }
        File file = new File(location.getArtifactLocation().getUri());
        Region region = location.getRegion();

        Region newRegion;
        switch (node) {
            case Block _ -> {
                int line = isEndToken ? region.getEndLine() : region.startLine;
                int column = isEndToken ? region.getEndColumn() - 1 : region.startColumn;
                newRegion = new Region(line, column, line, column + 1);
            }
            case CatchClause _,RecordDeclaration _ when isEndToken -> newRegion = new Region(region.getEndLine(), region.getEndColumn() - 1,
                    region.getEndLine(), region.getEndColumn());
            case LambdaExpression lambda -> {
                // The synthetic function spans the whole lambda; its body marks the end of the signature.
                Node body = lambda.getFunction() == null ? null : lambda.getFunction().getBody();
                PhysicalLocation bodyLocation = body == null ? null : body.getLocation();
                if (!locationContains(location, bodyLocation)) {
                    throw new IllegalStateException("Cannot create lambda token without a body source location inside the lambda in " + file);
                }
                Region bodyRegion = bodyLocation.getRegion();
                newRegion = new Region(region.startLine, region.startColumn, bodyRegion.startLine, bodyRegion.startColumn);
            }
            case AssignExpression _ ->
                    // The left-hand side starts at the assignment's start, leaving no usable prefix.
                    newRegion = region;
            case CallExpression call -> {
                PhysicalLocation calleeLocation = call.getCallee() == null ? null : call.getCallee().getLocation();
                newRegion = locationContains(location, calleeLocation) ? calleeLocation.getRegion() : region;
            }
            default -> {

                // Moved children retain their source locations, which must not define boundaries in another file.
                List<Region> childRegions = node.getAstChildren().stream().map(Node::getLocation)
                        .filter(childLocation -> locationContains(location, childLocation)).map(PhysicalLocation::getRegion).sorted().toList();

                if (childRegions.isEmpty()) {
                    newRegion = region;
                } else if (!isEndToken) {
                    // location encompasses the whole node AND all child nodes, not the syntactic element that represents the node.
                    // As an approximation for the missing values, we use the beginning and end of the child nodes, respectively, which is
                    // not ideal.
                    Region nextTokenRegion = childRegions.getFirst();
                    newRegion = new Region(region.startLine, region.startColumn, nextTokenRegion.startLine, nextTokenRegion.startColumn);
                } else {
                    Region previousTokenRegion = childRegions.getLast();
                    newRegion = new Region(previousTokenRegion.getEndLine(), previousTokenRegion.getEndColumn(), region.getEndLine(),
                            region.getEndColumn());
                }
            }
        }

        if (newRegion.startLine < 1 || newRegion.startColumn < 1 || newRegion.getEndLine() < 1 || newRegion.getEndColumn() < 1
                || newRegion.startLine > newRegion.getEndLine()
                || newRegion.startLine == newRegion.getEndLine() && newRegion.startColumn >= newRegion.getEndColumn()) {
            throw new IllegalStateException("Invalid source region %s for %s token in %s.".formatted(newRegion, type, file));
        }

        Name name = node.getName();
        addToken(type, file, newRegion, calculateLength(newRegion), name);
    }

    private boolean locationContains(PhysicalLocation outer, PhysicalLocation inner) {
        return outer != null && inner != null && outer.getArtifactLocation().getUri().equals(inner.getArtifactLocation().getUri())
                && regionContains(outer.getRegion(), inner.getRegion());
    }

    private boolean regionContains(Region outer, Region inner) {
        boolean startContained = outer.startLine < inner.startLine || (outer.startLine == inner.startLine && outer.startColumn <= inner.startColumn);
        boolean endContained = outer.getEndLine() > inner.getEndLine()
                || (outer.getEndLine() == inner.getEndLine() && outer.getEndColumn() >= inner.getEndColumn());
        return startContained && endContained;
    }

}
