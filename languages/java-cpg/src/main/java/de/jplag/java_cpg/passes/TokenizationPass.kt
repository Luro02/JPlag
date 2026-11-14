package de.jplag.java_cpg.passes

import de.fraunhofer.aisec.cpg.TranslationContext
import de.fraunhofer.aisec.cpg.TranslationResult
import de.fraunhofer.aisec.cpg.passes.TranslationResultPass
import de.fraunhofer.aisec.cpg.passes.configuration.DependsOn
import de.jplag.Token
import de.jplag.java_cpg.token.CpgTokenizer
import de.jplag.java_cpg.visitor.NodeOrderStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * This pass tokenizes the [TranslationResult].
 */
@DependsOn(CpgTransformationPass::class)
class TokenizationPass(ctx: TranslationContext) : TranslationResultPass(ctx) {

    private val tokenList = ArrayList<Token>()

    override fun cleanup() {
        logger.info("Found %d tokens".format(tokenList.size))
    }

    private val strategy = NodeOrderStrategy()

    override fun accept(translationResult: TranslationResult) {
        tokenList.clear()

        val tracker = requireNotNull(passConfig<GraphMutationPassConfiguration>()).mutationContext.provenanceTracker
        val listener = CpgTokenizer(tracker)

        strategy.walk(
            translationResult,
            { node -> listener.visit(node).ifPresent(tokenList::add) },
            { node -> listener.exit(node).ifPresent(tokenList::add) }
        )
        translationResult.scratch["tokenList"] = tokenList
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(TokenizationPass::class.java)
    }
}
