// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { Client } from '@stomp/stompjs'
import { watch } from 'vue'
import { getAccessToken, isAuthenticated } from '../auth/authService'

// Mirrors the backend's ChangeMessage (see WebSocketChangeBroadcaster) — a signal only,
// never entity data: `type` is the same CloudEvents type a Kafka consumer of
// cdrm.notifications.kafka.topic would see (e.g.
// "dev.juergenreiss.cdrm.release-history.promoted"), `subject` the changed entity's id.
// A handler decides what to refetch based on `type`'s prefix and fetches it through the
// normal, already-authorized REST API — this channel carries nothing sensitive.
export interface ChangeMessage {
  type: string
  subject: string
  time: string
}

// Module-level (not per-component) — one shared connection for the whole app, the same
// "shared registry" pattern useToast/useMenuVisibility already use. Raw WebSocket (no
// SockJS): brokerURL points the client straight at the backend's STOMP endpoint,
// proxied at /ws in dev (see vite.config.ts) — a reverse proxy in front of the built
// app needs to forward /ws with a WebSocket upgrade too (see README).
const handlers = new Set<(message: ChangeMessage) => void>()

const client = new Client({
  brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
  reconnectDelay: 5000,
  beforeConnect: async () => {
    // The JWT travels as a STOMP CONNECT frame header, not an HTTP header — the browser
    // WebSocket API can't set the latter (see WebSocketConfig's server-side docs).
    const token = await getAccessToken()
    client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {}
  },
  onConnect: () => {
    client.subscribe('/topic/changes', (frame) => {
      try {
        const message = JSON.parse(frame.body) as ChangeMessage
        handlers.forEach((handler) => handler(message))
      } catch {
        // Malformed frame — ignore it rather than let one bad message break the socket.
      }
    })
  },
})

watch(
  isAuthenticated,
  (authenticated) => {
    if (authenticated && !client.active) {
      client.activate()
    } else if (!authenticated && client.active) {
      client.deactivate()
    }
  },
  { immediate: true },
)

// Registers a handler for every incoming change message; returns an unsubscribe
// function — call it (e.g. from onUnmounted) when the component stops caring. The
// shared connection itself stays open for as long as the user is logged in,
// independent of any one component's lifecycle.
export function onChange(handler: (message: ChangeMessage) => void): () => void {
  handlers.add(handler)
  return () => handlers.delete(handler)
}
