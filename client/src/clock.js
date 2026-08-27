import { OUTPUT_LATENCY_MS, PING_WINDOW } from './config.js'

// Maps the server's System.nanoTime onto AudioContext.currentTime.
//
// It keeps a reference PAIR rather than a scalar offset: one server
// instant and the client time it corresponds to. Conversion subtracts two
// BigInts and only then divides into a double, so an instant of any
// magnitude converts exactly -- nanoTime routinely exceeds 2^53, where a
// double silently loses whole nanoseconds.
//
// The reference is the lowest-RTT sample in the recent window. Low RTT is
// the best available evidence that the round trip was symmetric, which is
// the one assumption this estimate rests on.
export function makeClock () {
  let samples = []
  let latencyMs = OUTPUT_LATENCY_MS

  const best = () =>
    samples.reduce((a, b) => (b.rtt < a.rtt ? b : a), samples[0])

  return {
    addSample ({ sentAt, receivedAt, serverNanos }) {
      samples.push({
        rtt: receivedAt - sentAt,
        clientTime: (sentAt + receivedAt) / 2,
        serverNanos
      })
      if (samples.length > PING_WINDOW) samples = samples.slice(-PING_WINDOW)
    },

    // Drop every sample. Call this on a reconnect: the sample window
    // otherwise survives it (PING_WINDOW is 8, a burst adds 5, so up to 3
    // pre-disconnect samples remain), and best() picks purely by lowest
    // RTT with no age bound -- a stale sample can anchor the new timeline
    // and, if it lands wrong, drop every cycle until it ages out.
    reset () { samples = [] },

    ready: () => samples.length > 0,
    rtt: () => (samples.length ? best().rtt : null),

    toAudioTime (serverNanos) {
      if (!samples.length) throw new Error('clock not synced yet')
      const ref = best()
      const deltaNs = serverNanos - ref.serverNanos   // exact, BigInt
      return ref.clientTime + Number(deltaNs) / 1e9 + latencyMs / 1000
    },

    setLatency (ms) { latencyMs = ms },
    latency: () => latencyMs
  }
}
