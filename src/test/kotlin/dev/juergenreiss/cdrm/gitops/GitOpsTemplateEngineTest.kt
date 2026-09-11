// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Exercises the actual GraalVM JS sandbox — this is the one place GitOpsTemplateEngine
// runs real scripts rather than being trusted against a mock (see GitOpsResolverTest).
class GitOpsTemplateEngineTest {

    private val engine = GitOpsTemplateEngine()

    private val context = GitOpsTemplateContext(
        gitRepoName = "http://localhost:3000/cdrm/gitops-demo.git",
        clusterName = "minikube",
        namespace = "p-qa-platform",
        productName = "Platform",
        stageName = "paris-qa",
        workloadName = "platform-interface-emea",
        releaseBinary = "registry.example.com/app:2.0",
        targetStage = "paris-qa",
    )

    @Test
    fun `returns the edits a script builds and returns`() {
        val script = "return [{gitBranch: 'main', filePath: 'a.yaml', yamlKeyPath: 'image', value: 'x'}]"

        val result = engine.evaluate(script, context)

        assertEquals(GitOpsTemplateResult.Success(listOf(GitOpsEdit("main", "a.yaml", "image", "x"))), result)
    }

    @Test
    fun `returns multiple edits, built with a loop, referencing every context variable`() {
        val script = """
            var edits = [];
            var files = ['a.yaml', 'b.yaml'];
            for (var i = 0; i < files.length; i++) {
                edits.push({
                    gitBranch: 'main',
                    filePath: namespace + '/' + workloadName + '/' + files[i],
                    yamlKeyPath: 'image',
                    value: gitRepoName + '|' + clusterName + '|' + productName + '|' + stageName + '|' + releaseBinary + '|' + targetStage,
                });
            }
            return edits;
        """.trimIndent()

        val result = engine.evaluate(script, context) as GitOpsTemplateResult.Success

        assertEquals(2, result.edits.size)
        assertEquals("p-qa-platform/platform-interface-emea/a.yaml", result.edits[0].filePath)
        assertEquals(
            "http://localhost:3000/cdrm/gitops-demo.git|minikube|Platform|paris-qa|registry.example.com/app:2.0|paris-qa",
            result.edits[0].value,
        )
    }

    @Test
    fun `reports a thrown error as Failed, not an exception`() {
        val result = engine.evaluate("throw new Error('boom')", context)

        assertTrue(result is GitOpsTemplateResult.Failed)
        assertTrue((result as GitOpsTemplateResult.Failed).reason.contains("boom"))
    }

    @Test
    fun `reports a syntax error as Failed`() {
        val result = engine.evaluate("this is not valid javascript {{{", context)

        assertTrue(result is GitOpsTemplateResult.Failed)
    }

    @Test
    fun `reports a non-array return value as Failed`() {
        val result = engine.evaluate("return 'not an array'", context)

        assertTrue(result is GitOpsTemplateResult.Failed)
        assertTrue((result as GitOpsTemplateResult.Failed).reason.contains("must return an array"))
    }

    @Test
    fun `reports a missing required field as Failed`() {
        val result = engine.evaluate("return [{gitBranch: 'main', filePath: 'a.yaml', yamlKeyPath: 'image'}]", context)

        assertTrue(result is GitOpsTemplateResult.Failed)
        assertTrue((result as GitOpsTemplateResult.Failed).reason.contains("value"))
    }

    @Test
    fun `reports a non-string field as Failed`() {
        val result = engine.evaluate("return [{gitBranch: 'main', filePath: 'a.yaml', yamlKeyPath: 'image', value: 42}]", context)

        assertTrue(result is GitOpsTemplateResult.Failed)
    }

    @Test
    fun `cannot reach any host object (no Java interop at all)`() {
        val result = engine.evaluate("Java.type('java.lang.System'); return []", context)

        assertTrue(result is GitOpsTemplateResult.Failed)
    }

    @Test
    fun `an infinite loop is stopped by the statement limit rather than hanging forever`() {
        val result = engine.evaluate("while (true) { }", context)

        assertTrue(result is GitOpsTemplateResult.Failed)
    }
}
