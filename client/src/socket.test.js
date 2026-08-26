import { expect, test, vi } from 'vitest'
import { connect, nextBackoff } from './socket.js'
import { RECONNECT_MAX_MS, RECONNECT_MIN_MS } from './config.js'

// A WebSocket stand-in the test drives by hand.
class FakeSocket {
  static instances = []
  constructor (url) {
    this.url = url
    this.sent = []
    this.readyState = 0
    FakeSocket.instances.push(this)
  }
  send (s) { this.sent.push(s) }
  close () { if (this.readyState === 3) return; this.readyState = 3; this.onclose && this.onclose() }
  open () { this.readyState = 1; this.onopen && this.onopen() }
  receive (s) { this.onmessage && this.onmessage({ data: s }) }
}

function harness (opts = {}) {
  FakeSocket.instances = []
  const timers = []
  const sock = connect('ws://x/hud', {
    WebSocketImpl: FakeSocket,
    setTimeoutImpl: (fn, ms) => { timers.push({ fn, ms }); return timers.length },
    ...opts
  })
  return { sock, timers, sockets: FakeSocket.instances }
}

test('backoff starts at the minimum and doubles to the cap', () => {
  expect(nextBackoff(null)).toBe(RECONNECT_MIN_MS)
  expect(nextBackoff(RECONNECT_MIN_MS)).toBe(RECONNECT_MIN_MS * 2)
  expect(nextBackoff(RECONNECT_MAX_MS)).toBe(RECONNECT_MAX_MS)
  expect(nextBackoff(RECONNECT_MAX_MS / 2 + 1)).toBe(RECONNECT_MAX_MS)
})

test('messages are decoded and passed on', () => {
  const onMessage = vi.fn()
  const { sockets } = harness({ onMessage })
  sockets[0].open()
  sockets[0].receive(JSON.stringify({ t: 'pong', id: 1 }))
  expect(onMessage).toHaveBeenCalledWith({ t: 'pong', id: 1 })
})

test('a malformed frame is ignored and does not kill the connection', () => {
  const onMessage = vi.fn()
  const { sockets, sock } = harness({ onMessage })
  sockets[0].open()
  sockets[0].receive('not json {{{')
  expect(onMessage).not.toHaveBeenCalled()
  expect(sock.isOpen()).toBe(true)
})

test('send encodes to JSON when open and drops when closed', () => {
  const { sockets, sock } = harness()
  sock.send({ t: 'ping', id: 1 })
  expect(sockets[0].sent).toEqual([])       // not open yet, dropped
  sockets[0].open()
  sock.send({ t: 'ping', id: 2 })
  expect(JSON.parse(sockets[0].sent[0])).toEqual({ t: 'ping', id: 2 })
})

test('a close schedules a reconnect at the minimum backoff', () => {
  const { sockets, timers } = harness()
  sockets[0].open()
  sockets[0].close()
  expect(timers[0].ms).toBe(RECONNECT_MIN_MS)
  timers[0].fn()
  expect(sockets.length).toBe(2)
})

test('repeated failures back off, and a successful open resets the delay', () => {
  const { sockets, timers } = harness()
  sockets[0].close()
  timers[0].fn()
  sockets[1].close()
  expect(timers[1].ms).toBe(RECONNECT_MIN_MS * 2)
  timers[1].fn()
  sockets[2].open()
  sockets[2].close()
  expect(timers[2].ms).toBe(RECONNECT_MIN_MS)
})

test('onOpen fires on every successful connection, including reconnects', () => {
  const onOpen = vi.fn()
  const { sockets, timers } = harness({ onOpen })
  sockets[0].open()
  sockets[0].close()
  timers[0].fn()
  sockets[1].open()
  expect(onOpen).toHaveBeenCalledTimes(2)
})

test('an explicit close stops reconnecting', () => {
  const { sockets, timers, sock } = harness()
  sockets[0].open()
  sock.close()
  expect(timers.length).toBe(0)
})

test('an explicit close during the backoff window does not reconnect', () => {
  const { sockets, timers, sock } = harness()
  sockets[0].open()
  sockets[0].close()            // unexpected drop, schedules a reconnect
  expect(timers.length).toBe(1)
  sock.close()                  // caller gives up while the timer is pending
  timers[0].fn()                // the pending timer fires anyway
  expect(sockets.length).toBe(1)   // no new socket was created
})
