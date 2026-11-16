// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.gitops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitOpsRepositoryControllerTest {

    @Test
    fun `repositories returns just the configured urls, never credentials`() {
        val properties = GitOpsProperties().apply {
            repositories = mutableListOf(
                GitOpsRepositoryConfig().apply {
                    url = "http://a.example.com/repo.git"
                    auth = GitAuthMode.BASIC
                    username = "secret-user"
                    password = "secret-password"
                },
            )
        }
        val controller = GitOpsRepositoryController(properties)

        assertEquals(listOf("http://a.example.com/repo.git"), controller.repositories())
    }
}
