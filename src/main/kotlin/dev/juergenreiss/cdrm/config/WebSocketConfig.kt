// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

// Pushes a lightweight "something changed, go refetch" signal to connected browsers —
// see WebSocketChangeBroadcaster and its two producers (ReleaseNotificationPublisher for
// this instance's own actions, KafkaChangeRelay for other instances', via the same Kafka
// topic already published to cdrm.notifications.kafka.topic). Messages carry no
// entity data, only a CloudEvents `type`/`subject` pair — the frontend's own
// already-authorized REST calls fetch the actual (permission-filtered) data, so this
// channel itself never needs per-user authorization, only "is this someone logged in".
//
// The initial HTTP handshake to /ws is deliberately left unauthenticated (see
// SecurityConfig's permitAll for it) — a browser's WebSocket API can't set an
// Authorization header on that request. Instead, the JWT travels as a STOMP CONNECT
// frame header (part of the message payload sent over the already-open socket, not an
// HTTP header), checked by the interceptor below before the connection is accepted.
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(private val jwtDecoder: JwtDecoder) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*")
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
                val accessor = StompHeaderAccessor.wrap(message)
                if (accessor.command == StompCommand.CONNECT) {
                    val token = accessor.getFirstNativeHeader("Authorization")?.removePrefix("Bearer ")?.trim()
                    val jwt = token?.takeIf { it.isNotEmpty() }?.let { runCatching { jwtDecoder.decode(it) }.getOrNull() }
                        ?: throw org.springframework.messaging.MessagingException("Missing or invalid token")
                    accessor.user = UsernamePasswordAuthenticationToken.authenticated(jwt.subject, null, emptyList())
                }
                return message
            }
        })
    }
}
