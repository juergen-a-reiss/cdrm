// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private fun repo(url: String, auth: GitAuthMode = GitAuthMode.NONE, username: String? = null, sshPrivateKeyPath: String? = null) =
    GitOpsRepositoryConfig().apply {
        this.url = url
        this.auth = auth
        this.username = username
        this.sshPrivateKeyPath = sshPrivateKeyPath
    }

class GitOpsPropertiesTest {

    @Test
    fun `find returns the entry matching by exact url`() {
        val properties = GitOpsProperties().apply {
            repositories = mutableListOf(repo("http://a.example.com/repo.git"), repo("http://b.example.com/repo.git"))
        }

        assertEquals("http://b.example.com/repo.git", properties.find("http://b.example.com/repo.git")?.url)
    }

    @Test
    fun `find returns null when nothing matches`() {
        val properties = GitOpsProperties().apply { repositories = mutableListOf(repo("http://a.example.com/repo.git")) }

        assertNull(properties.find("http://unconfigured.example.com/repo.git"))
    }

    @Test
    fun `validate passes for an empty list`() {
        GitOpsProperties().validate()
    }

    @Test
    fun `validate passes for a well-formed NONE-BASIC-SSH_KEY mix`() {
        val properties = GitOpsProperties().apply {
            repositories = mutableListOf(
                repo("http://a.example.com/repo.git"),
                repo("http://b.example.com/repo.git", auth = GitAuthMode.BASIC, username = "user"),
                repo("git@c.example.com:repo.git", auth = GitAuthMode.SSH_KEY, sshPrivateKeyPath = "/etc/cdrm/secrets/key"),
            )
        }

        properties.validate()
    }

    @Test
    fun `validate rejects a duplicate url`() {
        val properties = GitOpsProperties().apply {
            repositories = mutableListOf(repo("http://a.example.com/repo.git"), repo("http://a.example.com/repo.git"))
        }

        assertThrows(IllegalArgumentException::class.java) { properties.validate() }
    }

    @Test
    fun `validate rejects BASIC auth with no username`() {
        val properties = GitOpsProperties().apply {
            repositories = mutableListOf(repo("http://a.example.com/repo.git", auth = GitAuthMode.BASIC))
        }

        assertThrows(IllegalArgumentException::class.java) { properties.validate() }
    }

    @Test
    fun `validate rejects SSH_KEY auth with no key path`() {
        val properties = GitOpsProperties().apply {
            repositories = mutableListOf(repo("git@a.example.com:repo.git", auth = GitAuthMode.SSH_KEY))
        }

        assertThrows(IllegalArgumentException::class.java) { properties.validate() }
    }

    @Test
    fun `validate rejects a blank url`() {
        val properties = GitOpsProperties().apply { repositories = mutableListOf(repo("")) }

        assertThrows(IllegalArgumentException::class.java) { properties.validate() }
    }
}
