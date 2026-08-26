let lastCycle = null

export function resetSequence () { lastCycle = null }

// Turn one cycle message into scheduled audio events.
//
// Dropping is whole-cycle and deliberate. A cycle that arrives after its
// own start time cannot be played in time, and playing it anyway would
// dump every note of it at once -- the characteristic machine-gun burst
// of a sequencer catching up. Silence is the better failure.
export function scheduleCycle (msg, clock, now) {
  const gap = lastCycle !== null && msg.n !== lastCycle + 1
  lastCycle = msg.n

  if (!clock.ready()) return { events: [], dropped: true, gap }

  const t0 = BigInt(msg.t0)
  const startTime = clock.toAudioTime(t0)
  if (startTime <= now) return { events: [], dropped: true, gap }

  const events = msg.msgs
    .map(m => ({
      time: clock.toAudioTime(t0 + BigInt(m.d)),
      type: m.type,
      chan: m.chan,
      note: m.note,
      vel: m.vel
    }))
    .sort((a, b) => a.time - b.time)

  return { events, dropped: false, gap }
}
