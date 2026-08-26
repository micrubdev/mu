// Output latency: how far behind the JVM the browser renders. It has to
// cover network jitter, a GC pause on the server, and the AudioContext's
// own buffer. 80ms is comfortable on a LAN and inaudible as "late" unless
// you are in the same room as the JVM's own output.
export const OUTPUT_LATENCY_MS = 80

export const PING_INTERVAL_MS = 2000
export const PING_WINDOW = 8          // samples kept for the min-RTT estimate
export const RECONNECT_MIN_MS = 250
export const RECONNECT_MAX_MS = 4000
