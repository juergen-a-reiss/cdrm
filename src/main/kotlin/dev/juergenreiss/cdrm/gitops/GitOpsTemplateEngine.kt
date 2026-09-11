// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.springframework.stereotype.Component

// Every value a GitOpsNamespaceMode.TEMPLATE script can reference — bound directly as
// global JS variables (see GitOpsTemplateEngine.evaluate()), not passed as a function
// argument, so a script is just a plain sequence of statements ending in `return [...]`
// rather than needing to declare its own function signature.
data class GitOpsTemplateContext(
    val gitRepoName: String,
    val clusterName: String,
    val namespace: String,
    val productName: String,
    val stageName: String,
    val workloadName: String,
    val releaseBinary: String,
    val targetStage: String,
)

sealed interface GitOpsTemplateResult {
    data class Success(val edits: List<GitOpsEdit>) : GitOpsTemplateResult
    data class Failed(val reason: String) : GitOpsTemplateResult
}

// Runs a namespace's TEMPLATE script in a locked-down GraalVM JS sandbox — no
// filesystem/network/process/thread/environment access, no host object access at all
// (a script only ever sees the plain strings in GitOpsTemplateContext, nothing that
// could reach back into the JVM), and a statement budget so a runaway or malicious
// script can't hang a deploy indefinitely. The script text is wrapped as a function
// body so a plain `return [...]` (rather than requiring the user to declare their own
// function) works, matching how a devops user would naturally write "compute and return
// a list" rather than a full program. It must return an array of {gitBranch, filePath,
// yamlKeyPath, value} objects (all four required, all strings) — anything else,
// including the script throwing, is reported as a Failed result, same as any other
// deploy failure (never silently treated as "not GitOps-managed").
@Component
class GitOpsTemplateEngine {

    companion object {
        // Generous for what's realistically a short loop building a handful of edit
        // objects — not a real workload budget, just a backstop against an infinite
        // loop or accidental heavy computation.
        private const val STATEMENT_LIMIT = 200_000L
    }

    fun evaluate(script: String, context: GitOpsTemplateContext): GitOpsTemplateResult {
        return try {
            Context.newBuilder("js")
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .resourceLimits(ResourceLimits.newBuilder().statementLimit(STATEMENT_LIMIT, null).build())
                .build().use { ctx ->
                    val bindings = ctx.getBindings("js")
                    bindings.putMember("gitRepoName", context.gitRepoName)
                    bindings.putMember("clusterName", context.clusterName)
                    bindings.putMember("namespace", context.namespace)
                    bindings.putMember("productName", context.productName)
                    bindings.putMember("stageName", context.stageName)
                    bindings.putMember("workloadName", context.workloadName)
                    bindings.putMember("releaseBinary", context.releaseBinary)
                    bindings.putMember("targetStage", context.targetStage)
                    val result = ctx.eval("js", "(function() {\n$script\n})()")
                    parseEdits(result)
                }
        } catch (e: PolyglotException) {
            GitOpsTemplateResult.Failed("template script failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            GitOpsTemplateResult.Failed(e.message ?: "template script returned an invalid result")
        }
    }

    private fun parseEdits(result: Value): GitOpsTemplateResult {
        if (!result.hasArrayElements()) {
            return GitOpsTemplateResult.Failed("template script must return an array of edits, got: $result")
        }
        val edits = mutableListOf<GitOpsEdit>()
        for (i in 0 until result.arraySize) {
            val element = result.getArrayElement(i)
            edits += GitOpsEdit(
                branch = requiredStringMember(element, "gitBranch", i),
                filePath = requiredStringMember(element, "filePath", i),
                yamlKeyPath = requiredStringMember(element, "yamlKeyPath", i),
                value = requiredStringMember(element, "value", i),
            )
        }
        return GitOpsTemplateResult.Success(edits)
    }

    private fun requiredStringMember(element: Value, name: String, index: Long): String {
        val member = element.getMember(name)
        if (member == null || !member.isString) {
            throw IllegalArgumentException("template script's return value[$index].$name must be a string")
        }
        return member.asString()
    }
}
