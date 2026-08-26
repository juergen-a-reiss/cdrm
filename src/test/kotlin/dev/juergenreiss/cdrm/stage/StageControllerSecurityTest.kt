package dev.juergenreiss.cdrm.stage

import dev.juergenreiss.cdrm.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(StageController::class)
@Import(SecurityConfig::class)
@TestPropertySource(properties = ["spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:2305/realms/cdrm"])
class StageControllerSecurityTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var service: StageService

    // Never invoked: the jwt() post-processor injects an already-authenticated
    // token, bypassing real decoding. Present only so the resource-server DSL
    // has a JwtDecoder bean to wire up.
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    private val adminAuthority = SimpleGrantedAuthority("ROLE_cdrm-devops")

    private fun sampleResponse() = StageResponse(
        id = UUID.randomUUID(),
        name = "Draft",
        description = null,
        order = 1,
        deploymentPolicy = DeploymentPolicy.IMMEDIATE,
        kubernetesContext = null,
        namespacePrefix = null,
        clusters = emptyList(),
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    @Test
    fun `GET stages without token is unauthorized`() {
        mockMvc.perform(get("/stages")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET stages with valid token is authorized`() {
        given(service.findAll()).willReturn(emptyList())

        mockMvc.perform(get("/stages").with(jwt())).andExpect(status().isOk)
    }

    @Test
    fun `POST stages without admin role is forbidden`() {
        mockMvc.perform(
            post("/stages").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Draft","description":null,"order":1}""")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST stages with admin role is created`() {
        given(
            service.create(StageRequest(name = "Draft", description = null, order = 1, deploymentPolicy = DeploymentPolicy.IMMEDIATE))
        ).willReturn(sampleResponse())

        mockMvc.perform(
            post("/stages").with(jwt().authorities(adminAuthority))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Draft","description":null,"order":1,"deploymentPolicy":"IMMEDIATE"}""")
        ).andExpect(status().isCreated)
    }

    @Test
    fun `DELETE stages without admin role is forbidden`() {
        mockMvc.perform(delete("/stages/${UUID.randomUUID()}").with(jwt())).andExpect(status().isForbidden)
    }
}
