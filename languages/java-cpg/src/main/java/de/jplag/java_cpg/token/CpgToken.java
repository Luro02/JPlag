package de.jplag.java_cpg.token;

import static de.jplag.SharedTokenType.FILE_END;

import java.io.File;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.aisec.cpg.graph.Name;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.declarations.Declaration;
import de.fraunhofer.aisec.cpg.graph.declarations.RecordDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.TranslationUnitDeclaration;
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
 * This class represents a Token in the context of the CPG module of JPlag.
 */
public final class CpgToken extends Token {
    private static final Logger logger = LoggerFactory.getLogger(CpgToken.class);
    /**
     * This is used as the Token's length if the token spans multiple lines. The value is supposed to be greater than the
     * length of any sensible line of code.
     */
    private static final int MULTILINE_TOKEN_LENGTH = 1024;

    private final Name name;

    /**
     * Creates a new {@link CpgToken}.
     * @param tokenType the {@link TokenType}
     * @param file the {@link File} that contains the represented piece of code
     * @param region the {@link Region} representing the Token location
     * @param length the length of the represented code
     * @param name the name of the represented CPG node
     */
    private CpgToken(TokenType tokenType, File file, Region region, int length, Name name) {
        super(tokenType, file, region.startLine, region.startColumn, region.getEndLine(), region.getEndColumn(), length);
        this.name = name;
    }

    /**
     * Adds a new {@link Token} for the given {@link TokenType} and {@link Node}.
     * @param type the {@link TokenType}
     * @param node the represented {@link Node}
     * @param isEndToken true iff the token represents the end of a block
     */
    static Optional<CpgToken> fromNode(TokenType type, Node node, boolean isEndToken) {
        logger.debug("{} / {}", type, node);
        PhysicalLocation location = node.getLocation();

        if (Objects.isNull(location)) {
            if (node instanceof Declaration) {
                // implicit node, e.g., declaration of API classes - we do not want tokens for those.
                return Optional.empty();
            }

            throw new IllegalStateException(
                    "Cannot create %s token for %s without a source location.".formatted(type, node.getClass().getSimpleName()));
        }
        File file = new File(location.getArtifactLocation().getUri());
        Region newRegion = calculateNewRegion(node, location, file, isEndToken, type);

        return Optional.of(new CpgToken(type, file, newRegion, calculateLength(newRegion), node.getName()));
    }

    @Override
    public String toString() {
        return Objects.isNull(name) ? super.toString() : "%s(%s)".formatted(this.getType(), this.name);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CpgToken otherToken)) {
            return false;
        }
        return this.getType().equals(otherToken.getType());
    }

    @Override
    public int hashCode() {
        return getType().hashCode();
    }

    private static int calculateLength(Region region) {
        if (region.getEndLine() == region.startLine) {
            // CPG regions use exclusive end columns, just like JPlag tokens.
            return region.getEndColumn() - region.startColumn;
        }

        return MULTILINE_TOKEN_LENGTH;
    }

    static CpgToken fileEnd(TranslationUnitDeclaration translationUnitDeclaration) {
        return new CpgToken(FILE_END, new File(translationUnitDeclaration.getName().toString()), new Region(-1, -1, -1, -1), -1,
                translationUnitDeclaration.getName());
    }

    private static Region calculateNewRegion(Node node, PhysicalLocation location, File file, boolean isEndToken, TokenType type) {
        Region region = location.getRegion();

        Region newRegion = switch (node) {
            case Block _ -> {
                if (isEndToken) {
                    yield singleLetterRegion(region.getEndLine(), region.getEndColumn() - 1);
                } else {
                    yield singleLetterRegion(region.startLine, region.startColumn);
                }
            }
            case CatchClause _,RecordDeclaration _ when isEndToken -> singleLetterRegion(region.getEndLine(), region.getEndColumn() - 1);
            case LambdaExpression lambda -> lambdaSignatureRegion(lambda, location, file);
            // The left-hand side starts at the assignment's start, leaving no usable prefix.
            case AssignExpression _ -> region;
            case CallExpression call -> {
                PhysicalLocation calleeLocation = call.getCallee() == null ? null : call.getCallee().getLocation();
                yield locationContains(location, calleeLocation) ? calleeLocation.getRegion() : region;
            }
            default -> regionOutsideChildren(node, location, isEndToken);
        };

        if (newRegion.startLine < 1 || newRegion.startColumn < 1 || newRegion.getEndLine() < 1 || newRegion.getEndColumn() < 1
                || newRegion.startLine > newRegion.getEndLine()
                || newRegion.startLine == newRegion.getEndLine() && newRegion.startColumn >= newRegion.getEndColumn()) {
            throw new IllegalStateException("Invalid source region %s for %s token in %s.".formatted(newRegion, type, file));
        }

        return newRegion;
    }

    private static Region prefixUntil(Region outer, Region boundary) {
        return new Region(outer.startLine, outer.startColumn, boundary.startLine, boundary.startColumn);
    }

    private static Region suffixAfter(Region outer, Region boundary) {
        return new Region(boundary.getEndLine(), boundary.getEndColumn(), outer.getEndLine(), outer.getEndColumn());
    }

    private static Region regionOutsideChildren(Node node, PhysicalLocation location, boolean isEndToken) {
        Comparator<Region> boundaryOrder = isEndToken ? Comparator.reverseOrder() : Comparator.naturalOrder();

        // Moved children retain their source locations, which must not define boundaries in another file.
        Optional<Region> boundary = node.getAstChildren().stream().map(Node::getLocation)
                .filter(childLocation -> locationContains(location, childLocation)).map(PhysicalLocation::getRegion)
                // In combination with the isEndToken this will return max for end tokens, and min for start tokens
                .min(boundaryOrder);

        Region region = location.getRegion();
        return boundary.map(childRegion -> isEndToken ? suffixAfter(region, childRegion) : prefixUntil(region, childRegion)).orElse(region);
    }

    private static Region lambdaSignatureRegion(LambdaExpression lambda, PhysicalLocation location, File file) {
        // The synthetic function spans the whole lambda; its body marks the end of the signature.
        Node body = lambda.getFunction() == null ? null : lambda.getFunction().getBody();

        PhysicalLocation bodyLocation = body == null ? null : body.getLocation();
        if (!locationContains(location, bodyLocation)) {
            throw new IllegalStateException("Cannot create lambda token without a body source location inside the lambda in " + file);
        }

        return prefixUntil(location.getRegion(), bodyLocation.getRegion());
    }

    private static Region singleLetterRegion(int line, int column) {
        return new Region(line, column, line, column + 1);
    }

    private static boolean regionContains(Region outer, Region inner) {
        boolean startContained = outer.startLine < inner.startLine || (outer.startLine == inner.startLine && outer.startColumn <= inner.startColumn);
        boolean endContained = outer.getEndLine() > inner.getEndLine()
                || (outer.getEndLine() == inner.getEndLine() && outer.getEndColumn() >= inner.getEndColumn());
        return startContained && endContained;
    }

    private static boolean locationContains(PhysicalLocation outer, PhysicalLocation inner) {
        return outer != null && inner != null && outer.getArtifactLocation().getUri().equals(inner.getArtifactLocation().getUri())
                && regionContains(outer.getRegion(), inner.getRegion());
    }
}
