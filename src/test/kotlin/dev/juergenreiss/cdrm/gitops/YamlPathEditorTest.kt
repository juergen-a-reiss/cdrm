// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class YamlPathEditorTest {

    @Suppress("UNCHECKED_CAST")
    private fun load(yaml: String): MutableMap<String, Any?> = Yaml().load(yaml) as MutableMap<String, Any?>

    @Test
    fun `sets a value at a nested key path with a list index, the README's own example shape`() {
        val root = load(
            """
            spec:
              template:
                spec:
                  containers:
                    - name: app
                      image: old:1.0
            """.trimIndent()
        )

        YamlPathEditor.setValue(root, "spec.template.spec.containers[0].image", "new:2.0")

        @Suppress("UNCHECKED_CAST")
        val containers = ((root["spec"] as Map<String, Any?>)["template"] as Map<String, Any?>)
            .let { it["spec"] as Map<String, Any?> }["containers"] as List<Map<String, Any?>>
        assertEquals("new:2.0", containers[0]["image"])
    }

    @Test
    fun `sets a top-level key with no list index`() {
        val root = load("image: old:1.0")

        YamlPathEditor.setValue(root, "image", "new:2.0")

        assertEquals("new:2.0", root["image"])
    }

    @Test
    fun `throws when a key in the path is missing`() {
        val root = load("spec: {}")

        assertThrows(IllegalArgumentException::class.java) {
            YamlPathEditor.setValue(root, "spec.template.image", "new:2.0")
        }
    }

    @Test
    fun `throws when a list index is out of bounds`() {
        val root = load("containers: [{image: old:1.0}]")

        assertThrows(IllegalArgumentException::class.java) {
            YamlPathEditor.setValue(root, "containers[5].image", "new:2.0")
        }
    }

    @Test
    fun `throws when expecting a mapping but finding something else`() {
        val root = load("image: old:1.0")

        assertThrows(IllegalArgumentException::class.java) {
            YamlPathEditor.setValue(root, "image.tag", "new:2.0")
        }
    }
}
