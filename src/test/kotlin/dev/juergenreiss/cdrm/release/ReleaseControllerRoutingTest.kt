package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

// GET /releases/history and GET /releases/{id} are both two-segment paths under
// /releases — this confirms Spring resolves the literal "history" segment to
// historyOverview() rather than treating it as an {id} that then fails UUID parsing.
@WebMvcTest(ReleaseController::class)
@Import(SecurityConfig::class)
@TestPropertySource(properties = ["spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:2305/realms/cdrm"])
class ReleaseControllerRoutingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var service: ReleaseService

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Test
    fun `GET releases history routes to the overview endpoint, not findById`() {
        given(service.historyOverview()).willReturn(ReleaseHistoryPageResponse(content = emptyList(), totalElements = 0, page = 0, size = 25))

        mockMvc.perform(get("/releases/history").with(jwt())).andExpect(status().isOk)
    }

    @Test
    fun `GET releases by id still routes to findById`() {
        val id = UUID.randomUUID()
        given(service.findById(id)).willThrow(ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND))

        mockMvc.perform(get("/releases/$id").with(jwt())).andExpect(status().isNotFound)
    }
}
