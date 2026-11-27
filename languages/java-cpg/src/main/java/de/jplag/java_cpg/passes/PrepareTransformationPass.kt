package de.jplag.java_cpg.passes

import de.fraunhofer.aisec.cpg.TranslationContext
import de.fraunhofer.aisec.cpg.TranslationResult
import de.fraunhofer.aisec.cpg.passes.ImportResolver
import de.fraunhofer.aisec.cpg.passes.configuration.DependsOn
import de.fraunhofer.aisec.cpg.passes.configuration.ExecuteBefore
import de.jplag.java_cpg.transformation.GraphTransformation

/**
 * This pass handles the transformations that are required in the pipeline of the Tokenization process.
 */
@DependsOn(ImportResolver::class)
@ExecuteBefore(AstTransformationPass::class)
class PrepareTransformationPass(ctx: TranslationContext) : ATransformationPass(ctx) {

    override fun accept(t: TranslationResult) {
        // This will execute before any transformation to the graph occurred.
        // Transformations may change, add or remove nodes, which changes their identity.
        //
        // To ensure reliable comparisons between token streams (for the analysis module), the ids are tracked here:
        super.accept(t)
        mutationContext.tokenIdentities.initialize(t)
    }

    override fun getPhaseSpecificTransformations(): List<GraphTransformation> {
        return passConfig<TransformationPassConfiguration>()?.transformations ?: emptyList()
    }
}
