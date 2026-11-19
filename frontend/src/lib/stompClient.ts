import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import { API_BASE_URL } from '../api/client'
import { readToken } from './tokenStorage'

/**
 * One STOMP connection per tab, shared by every live view (ref-counted): opened when the first view
 * starts watching a topic, closed 10 s after the last one stops (so page-to-page navigation doesn't
 * reconnect). Reconnects with exponential backoff and jitter; every topic is re-subscribed after a
 * reconnect and its `onSubscribed` runs, which is where views resync.
 */
export type LiveStatus = 'idle' | 'connecting' | 'live' | 'reconnecting'

const WS_URL = `${API_BASE_URL.replace(/^http/, 'ws')}/ws` // http→ws, https→wss
const MIN_DELAY_MS = 1_000
const MAX_DELAY_MS = 30_000
const IDLE_CLOSE_MS = 10_000

type Topic = {
  destination: string
  onMessage: (body: string) => void
  onSubscribed: () => void
  subscription: StompSubscription | null
}

const topics = new Set<Topic>()
const listeners = new Set<() => void>()
let status: LiveStatus = 'idle'
let everConnected = false
let delayMs = MIN_DELAY_MS
let closeTimer: number | undefined
/** A token the server refused (INVALID_TOKEN): don't send it again, connect anonymously instead. */
let refusedToken: string | null = null

function setStatus(next: LiveStatus) {
  if (status === next) return
  status = next
  listeners.forEach((listener) => listener())
}

function subscribe(topic: Topic) {
  if (!client.connected) return
  topic.subscription = client.subscribe(topic.destination, (message: IMessage) => topic.onMessage(message.body))
  topic.onSubscribed()
}

const client: Client = new Client({
  brokerURL: WS_URL,
  reconnectDelay: MIN_DELAY_MS,
  heartbeatIncoming: 10_000,
  heartbeatOutgoing: 10_000,
  beforeConnect: () => {
    // The token goes in CONNECT (browsers can't send headers on the handshake). Seat topics are public,
    // so no token (or a refused one) still connects.
    const token = readToken()?.accessToken ?? null
    client.connectHeaders = token && token !== refusedToken ? { Authorization: `Bearer ${token}` } : {}
    setStatus(everConnected ? 'reconnecting' : 'connecting')
  },
  onConnect: () => {
    everConnected = true
    delayMs = MIN_DELAY_MS
    client.reconnectDelay = MIN_DELAY_MS
    setStatus('live')
    topics.forEach(subscribe)
  },
  onStompError: (frame) => {
    if (frame.headers.message === 'INVALID_TOKEN') refusedToken = readToken()?.accessToken ?? null
  },
  onWebSocketClose: () => {
    topics.forEach((topic) => {
      topic.subscription = null
    })
    if (!client.active) return // deactivated on purpose
    setStatus(everConnected ? 'reconnecting' : 'connecting')
    // Backoff for the next attempt: 1 s, 2 s, 4 s … 30 s, with jitter so tabs don't reconnect in lockstep
    delayMs = Math.min(delayMs * 2, MAX_DELAY_MS)
    client.reconnectDelay = Math.round(delayMs / 2 + Math.random() * (delayMs / 2))
  },
})

/** Watches a destination until the returned function is called. */
export function watchTopic(destination: string, onMessage: (body: string) => void, onSubscribed: () => void): () => void {
  window.clearTimeout(closeTimer)
  const topic: Topic = { destination, onMessage, onSubscribed, subscription: null }
  topics.add(topic)
  if (!client.active) client.activate()
  else subscribe(topic)

  return () => {
    if (client.connected) topic.subscription?.unsubscribe()
    topics.delete(topic)
    if (topics.size === 0) {
      closeTimer = window.setTimeout(() => {
        if (topics.size > 0) return
        everConnected = false
        void client.deactivate()
        setStatus('idle')
      }, IDLE_CLOSE_MS)
    }
  }
}

/** For useSyncExternalStore. */
export function subscribeLiveStatus(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function getLiveStatus(): LiveStatus {
  return status
}
