package de.jplag.java_cpg.token;

import static de.jplag.java_cpg.token.CpgTokenType.ASSERT_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.ASSIGNMENT;
import static de.jplag.java_cpg.token.CpgTokenType.BREAK;
import static de.jplag.java_cpg.token.CpgTokenType.CASE_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.CATCH_CLAUSE_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.CATCH_CLAUSE_END;
import static de.jplag.java_cpg.token.CpgTokenType.CONSTRUCTOR_CALL;
import static de.jplag.java_cpg.token.CpgTokenType.CONTINUE;
import static de.jplag.java_cpg.token.CpgTokenType.DEFAULT_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.DO_WHILE_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.DO_WHILE_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.DO_WHILE_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.ELSE_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.ELSE_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.ENUM_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.ENUM_DECL_END;
import static de.jplag.java_cpg.token.CpgTokenType.ENUM_ELEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.FIELD_DECL;
import static de.jplag.java_cpg.token.CpgTokenType.FINALLY_CLAUSE_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.FINALLY_CLAUSE_END;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.FOR_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.GOTO_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.IF_BLOCK_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.IF_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.IF_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.INCLUDE;
import static de.jplag.java_cpg.token.CpgTokenType.LAMBDA_EXPRESSION;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_BODY_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_BODY_END;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_CALL;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_PARAM;
import static de.jplag.java_cpg.token.CpgTokenType.NEW_ARRAY;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_END;
import static de.jplag.java_cpg.token.CpgTokenType.RETURN;
import static de.jplag.java_cpg.token.CpgTokenType.SWITCH_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.SWITCH_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.SWITCH_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.SYNCHRONIZED_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.SYNCHRONIZED_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.SYNCHRONIZED_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.THROW;
import static de.jplag.java_cpg.token.CpgTokenType.TRY_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.TRY_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.TRY_STATEMENT;
import static de.jplag.java_cpg.token.CpgTokenType.VARIABLE_DECL;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_BLOCK_END;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_BLOCK_START;
import static de.jplag.java_cpg.token.CpgTokenType.WHILE_STATEMENT;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.declarations.EnumConstantDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.EnumDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.FieldDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.IncludeDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.MethodDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.ParameterDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.RecordDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.TranslationUnitDeclaration;
import de.fraunhofer.aisec.cpg.graph.declarations.VariableDeclaration;
import de.fraunhofer.aisec.cpg.graph.statements.AssertStatement;
import de.fraunhofer.aisec.cpg.graph.statements.BreakStatement;
import de.fraunhofer.aisec.cpg.graph.statements.CaseStatement;
import de.fraunhofer.aisec.cpg.graph.statements.CatchClause;
import de.fraunhofer.aisec.cpg.graph.statements.ContinueStatement;
import de.fraunhofer.aisec.cpg.graph.statements.DefaultStatement;
import de.fraunhofer.aisec.cpg.graph.statements.DoStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ForEachStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ForStatement;
import de.fraunhofer.aisec.cpg.graph.statements.GotoStatement;
import de.fraunhofer.aisec.cpg.graph.statements.IfStatement;
import de.fraunhofer.aisec.cpg.graph.statements.ReturnStatement;
import de.fraunhofer.aisec.cpg.graph.statements.Statement;
import de.fraunhofer.aisec.cpg.graph.statements.SwitchStatement;
import de.fraunhofer.aisec.cpg.graph.statements.SynchronizedStatement;
import de.fraunhofer.aisec.cpg.graph.statements.TryStatement;
import de.fraunhofer.aisec.cpg.graph.statements.WhileStatement;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.AssignExpression;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Block;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.CallExpression;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.ConstructExpression;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.LambdaExpression;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.NewArrayExpression;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.UnaryOperator;
import de.jplag.java_cpg.transformation.ProvenanceTracker;

/**
 * This class translates {@link Node}s into {@link CpgToken}s.
 */
public class CpgTokenizer {
    private final ProvenanceTracker provenanceTracker;
    private final Map<Block, BlockKind> blockKinds;

    /**
     * Creates a new {@link CpgTokenizer}.
     * @param provenanceTracker the {@link ProvenanceTracker} that contains the provenance for the nodes
     */
    public CpgTokenizer(ProvenanceTracker provenanceTracker) {
        this.provenanceTracker = provenanceTracker;
        this.blockKinds = new IdentityHashMap<>();
    }

    private void registerBlock(Statement statement, BlockKind kind) {
        if (statement instanceof Block block) {
            blockKinds.put(block, kind);
        }
    }

    /**
     * Handles a node visit and registers markers for its structural blocks.
     * @param node the visited node
     * @return the token for this visit, if the node produces one
     */
    public Optional<CpgToken> visit(Node node) {
        CpgTokenType type = switch (node) {
            case EnumConstantDeclaration _ -> ENUM_ELEMENT;
            case EnumDeclaration _ -> ENUM_DECL_BEGIN;
            case FieldDeclaration _ -> FIELD_DECL;
            case IncludeDeclaration _ -> INCLUDE;
            case MethodDeclaration methodDeclaration -> {
                registerBlock(methodDeclaration.getBody(), BlockKind.METHOD_BODY);
                yield METHOD_DECL_BEGIN;
            }
            case NewArrayExpression _ -> NEW_ARRAY;
            case ParameterDeclaration _ -> METHOD_PARAM;
            case RecordDeclaration _ -> RECORD_DECL_BEGIN;
            case VariableDeclaration _ -> VARIABLE_DECL;
            case AssertStatement _ -> ASSERT_STATEMENT;
            case BreakStatement _ -> BREAK;
            case DefaultStatement _ -> DEFAULT_STATEMENT;
            case CaseStatement _ -> CASE_STATEMENT;
            case CatchClause _ -> CATCH_CLAUSE_BEGIN;
            case ContinueStatement _ -> CONTINUE;
            case DoStatement doStatement -> {
                registerBlock(doStatement.getStatement(), BlockKind.DO_WHILE_BODY);
                yield DO_WHILE_STATEMENT;
            }
            case ForEachStatement forEachStatement -> {
                registerBlock(forEachStatement.getStatement(), BlockKind.FOR_BODY);
                yield FOR_STATEMENT;
            }
            case ForStatement forStatement -> {
                registerBlock(forStatement.getStatement(), BlockKind.FOR_BODY);
                yield FOR_STATEMENT;
            }
            case GotoStatement _ -> GOTO_STATEMENT;
            case IfStatement ifStatement -> {
                registerBlock(ifStatement.getThenStatement(), BlockKind.IF_THEN);
                registerBlock(ifStatement.getElseStatement(), BlockKind.IF_ELSE);
                yield IF_STATEMENT;
            }
            case LambdaExpression _ -> LAMBDA_EXPRESSION;
            // implicit return without return value
            case ReturnStatement returnStatement when returnStatement.getLocation() != null -> RETURN;
            case SwitchStatement switchStatement -> {
                registerBlock(switchStatement.getStatement(), BlockKind.SWITCH_BODY);
                yield SWITCH_STATEMENT;
            }
            case SynchronizedStatement synchronizedStatement -> {
                registerBlock(synchronizedStatement.getBlock(), BlockKind.SYNCHRONIZED_BODY);
                yield SYNCHRONIZED_STATEMENT;
            }
            case TryStatement tryStatement -> {
                registerBlock(tryStatement.getTryBlock(), BlockKind.TRY_BODY);
                registerBlock(tryStatement.getFinallyBlock(), BlockKind.FINALLY_BODY);
                yield TRY_STATEMENT;
            }
            case UnaryOperator unaryOperator when "throw".equals(unaryOperator.getOperatorCode()) -> THROW;
            case WhileStatement whileStatement -> {
                registerBlock(whileStatement.getStatement(), BlockKind.WHILE_BODY);
                yield WHILE_STATEMENT;
            }
            case AssignExpression _ -> ASSIGNMENT;
            case Block block -> {
                var kind = blockKinds.get(block);
                yield kind == null ? null : kind.opening;
            }
            case ConstructExpression _ -> CONSTRUCTOR_CALL;
            case CallExpression _ -> METHOD_CALL;
            default -> null;
        };

        return Optional.ofNullable(type).flatMap(tokenType -> CpgToken.fromNode(tokenType, node, false, provenanceTracker.get(node)));
    }

    /**
     * Handles a node exit and releases its structural block markers.
     * @param node the exited node
     * @return the token for this exit, if the node produces one
     */
    public Optional<CpgToken> exit(Node node) {
        if (node instanceof TranslationUnitDeclaration translationUnitDeclaration) {
            return Optional.of(CpgToken.fileEnd(translationUnitDeclaration));
        }

        CpgTokenType type = switch (node) {
            case EnumDeclaration _ -> ENUM_DECL_END;
            case RecordDeclaration _ -> RECORD_DECL_END;
            case CatchClause _ -> CATCH_CLAUSE_END;
            case Block block -> {
                var kind = blockKinds.remove(block);
                yield kind == null ? null : kind.closing;
            }
            default -> null;
        };

        return Optional.ofNullable(type).flatMap(tokenType -> CpgToken.fromNode(tokenType, node, true, provenanceTracker.get(node)));
    }

    private enum BlockKind {
        METHOD_BODY(METHOD_BODY_BEGIN, METHOD_BODY_END),
        FOR_BODY(FOR_BLOCK_BEGIN, FOR_BLOCK_END),
        DO_WHILE_BODY(DO_WHILE_BLOCK_START, DO_WHILE_BLOCK_END),
        IF_THEN(IF_BLOCK_BEGIN, IF_BLOCK_END),
        IF_ELSE(ELSE_BLOCK_BEGIN, ELSE_BLOCK_END),
        WHILE_BODY(WHILE_BLOCK_START, WHILE_BLOCK_END),
        TRY_BODY(TRY_BLOCK_START, TRY_BLOCK_END),
        SWITCH_BODY(SWITCH_BLOCK_START, SWITCH_BLOCK_END),
        SYNCHRONIZED_BODY(SYNCHRONIZED_BLOCK_START, SYNCHRONIZED_BLOCK_END),
        FINALLY_BODY(FINALLY_CLAUSE_BEGIN, FINALLY_CLAUSE_END);

        private final CpgTokenType opening;
        private final CpgTokenType closing;

        BlockKind(CpgTokenType opening, CpgTokenType closing) {
            this.opening = opening;
            this.closing = closing;
        }
    }
}
