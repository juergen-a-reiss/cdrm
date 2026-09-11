// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

// Sets a value at a path within an already-parsed YAML tree (nested MutableMap<String,
// Any?> / List<Any?>, as produced by SnakeYAML's Yaml().load()) — just enough path
// grammar for what a K8sNamespaceGitopsConfig.yamlExpression needs: dot-separated keys,
// each optionally followed by [n] for a list index, e.g.
// "spec.template.spec.containers[0].image".
object YamlPathEditor {

    private val SEGMENT = Regex("""^([^\[\]]+)(?:\[(\d+)])?$""")

    fun setValue(root: MutableMap<String, Any?>, path: String, value: String) {
        val segments = path.split(".").map(::parseSegment)
        var current: Any = root
        for ((index, segment) in segments.withIndex()) {
            if (index == segments.lastIndex) {
                setAt(current, segment, value)
            } else {
                current = navigate(current, segment)
            }
        }
    }

    // Read-only counterpart of setValue — used by the "Test" template action (see
    // GitOpsTemplateTestService) to report whether a computed edit's yamlKeyPath would
    // actually resolve in the target file, without writing anything. A malformed path
    // (parseSegment) or a missing/wrong-shaped intermediate (navigate) is reported as
    // "doesn't exist" rather than propagating the exception — this is advisory feedback
    // for a devops user iterating on a script, not an operation that must succeed.
    fun pathExists(root: Map<String, Any?>, path: String): Boolean {
        return try {
            val segments = path.split(".").map(::parseSegment)
            var current: Any = root
            for ((index, segment) in segments.withIndex()) {
                if (index == segments.lastIndex) {
                    return existsAt(current, segment)
                }
                current = navigate(current, segment)
            }
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private data class Segment(val key: String, val index: Int?)

    private fun parseSegment(raw: String): Segment {
        val match = SEGMENT.matchEntire(raw)
            ?: throw IllegalArgumentException("Invalid YAML key path segment '$raw'")
        return Segment(match.groupValues[1], match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt())
    }

    @Suppress("UNCHECKED_CAST")
    private fun navigate(current: Any, segment: Segment): Any {
        val map = current as? Map<String, Any?>
            ?: throw IllegalArgumentException("Expected a mapping at '${segment.key}', found ${current.javaClass.simpleName}")
        val value = map[segment.key]
            ?: throw IllegalArgumentException("Key '${segment.key}' not found")
        if (segment.index == null) return value
        val list = value as? List<Any?>
            ?: throw IllegalArgumentException("Expected a list at '${segment.key}', found ${value.javaClass.simpleName}")
        return list.getOrNull(segment.index)
            ?: throw IllegalArgumentException("Index ${segment.index} out of bounds at '${segment.key}'")
    }

    private fun existsAt(current: Any, segment: Segment): Boolean {
        val map = current as? Map<String, Any?> ?: return false
        if (segment.index == null) return map.containsKey(segment.key)
        val list = map[segment.key] as? List<Any?> ?: return false
        return segment.index in list.indices
    }

    @Suppress("UNCHECKED_CAST")
    private fun setAt(current: Any, segment: Segment, value: String) {
        val map = current as? MutableMap<String, Any?>
            ?: throw IllegalArgumentException("Expected a mapping at '${segment.key}', found ${current.javaClass.simpleName}")
        if (segment.index == null) {
            map[segment.key] = value
            return
        }
        val list = map[segment.key] as? MutableList<Any?>
            ?: throw IllegalArgumentException("Expected a list at '${segment.key}'")
        if (segment.index !in list.indices) {
            throw IllegalArgumentException("Index ${segment.index} out of bounds at '${segment.key}'")
        }
        list[segment.index] = value
    }
}
