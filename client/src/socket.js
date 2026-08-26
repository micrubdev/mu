import { RECONNECT_MAX_MS, RECONNECT_MIN_MS } from './config.js'

export function nextBackoff (previous) {
  if (previous == null) return RECONNECT_MIN_MS
  return Math.min(previous * 2, RECONNECT_MAX_MS)
}

// A JSON WebSocket that reconnects itself.
//
// WebSocketImpl and setTimeoutImpl are injected so the whole reconnect
// state machine is testable without a network or a wall clock -- that
// state machine is where this kind of code actually goes wrong.
export function connect (url, {
  onMessage = () => {},
  onOpen = () => {},
  onClose = () => {},
  WebSocketImpl = globalThis.WebSocket,
  setTimeoutImpl = globalThis.setTimeout
} = {}) {
  let ws = null
  let backoff = null
  let stopped = false

  const open = () => {
    if (stopped) return
    ws = new WebSocketImpl(url)
    ws.onopen = () => { backoff = null; onOpen() }
    ws.onmessage = ev => {
      let parsed
      try { parsed = JSON.parse(ev.data) } catch { return }
      onMessage(parsed)
    }
    ws.onclose = () => {
      onClose()
      if (stopped) return
      backoff = nextBackoff(backoff)
      setTimeoutImpl(open, backoff)
    }
  }

  open()

  return {
    send (obj) {
      if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj))
    },
    isOpen: () => Boolean(ws) && ws.readyState === 1,
    close () { stopped = true; if (ws) ws.close() }
  }
}
