import { beforeEach, expect, test } from 'vitest'
import { makeClock } from './clock.js'
import { resetSequence, scheduleCycle } from './schedule.js'

const T0 = 5_000_000_000n

function syncedClock () {
  const c = makeClock()
  // Server instant T0 corresponds to audio time 10.0, zero latency for
  // arithmetic that is easy to read in the assertions.
  c.addSample({ sentAt: 10.0, receivedAt: 10.0, serverNanos: T0 })
  c.setLatency(0)
  return c
}

const cycle = (n, msgs) => ({
  t: 'cycle', n, t0: T0.toString(), npc: 500_000_000, msgs
})

beforeEach(() => resetSequence())

test('a message at the cycle anchor lands at the anchor audio time', () => {
  const { events, dropped } = scheduleCycle(
    cycle(1, [{ d: 0, type: 'note-on', chan: 0, note: 36, vel: 0.8 }]),
    syncedClock(), 9.0)
  expect(dropped).toBe(false)
  expect(events).toEqual([{ time: 10.0, type: 'note-on', chan: 0, note: 36, vel: 0.8 }])
})

test('a delta becomes seconds after the anchor', () => {
  const { events } = scheduleCycle(
    cycle(1, [{ d: 250_000_000, type: 'note-on', chan: 0, note: 36, vel: 0.8 }]),
    syncedClock(), 9.0)
  expect(events[0].time).toBeCloseTo(10.25, 9)
})

test('events come out in ascending time order', () => {
  const { events } = scheduleCycle(
    cycle(1, [
      { d: 400_000_000, type: 'note-off', chan: 0, note: 36 },
      { d: 0, type: 'note-on', chan: 0, note: 36, vel: 0.8 }
    ]),
    syncedClock(), 9.0)
  expect(events.map(e => e.time)).toEqual([...events.map(e => e.time)].sort((a, b) => a - b))
})

test('a note-off past the cycle end is kept, not clamped', () => {
  const { events } = scheduleCycle(
    cycle(1, [{ d: 900_000_000, type: 'note-off', chan: 0, note: 36 }]),
    syncedClock(), 9.0)
  expect(events[0].time).toBeCloseTo(10.9, 9)
})

test('a cycle whose start has already passed is dropped whole', () => {
  const { events, dropped } = scheduleCycle(
    cycle(1, [
      { d: 0, type: 'note-on', chan: 0, note: 36, vel: 0.8 },
      { d: 400_000_000, type: 'note-on', chan: 0, note: 40, vel: 0.8 }
    ]),
    syncedClock(), 10.5)   // now is past the anchor
  expect(dropped).toBe(true)
  expect(events).toEqual([])
})

test('an unsynced clock drops the cycle instead of throwing', () => {
  const { dropped, events } = scheduleCycle(
    cycle(1, [{ d: 0, type: 'note-on', chan: 0, note: 36, vel: 0.8 }]),
    makeClock(), 9.0)
  expect(dropped).toBe(true)
  expect(events).toEqual([])
})

test('consecutive cycles report no gap', () => {
  const c = syncedClock()
  expect(scheduleCycle(cycle(1, []), c, 9.0).gap).toBe(false)
  expect(scheduleCycle(cycle(2, []), c, 9.0).gap).toBe(false)
})

test('a skipped cycle number reports a gap', () => {
  const c = syncedClock()
  scheduleCycle(cycle(1, []), c, 9.0)
  expect(scheduleCycle(cycle(4, []), c, 9.0).gap).toBe(true)
})

test('the first cycle after a reset is not a gap', () => {
  const c = syncedClock()
  scheduleCycle(cycle(1, []), c, 9.0)
  resetSequence()
  expect(scheduleCycle(cycle(99, []), c, 9.0).gap).toBe(false)
})

test('precision holds for instants beyond 2^53', () => {
  const big = 9_007_199_254_740_993n
  const c = makeClock()
  c.addSample({ sentAt: 10.0, receivedAt: 10.0, serverNanos: big })
  c.setLatency(0)
  const msg = { t: 'cycle', n: 1, t0: big.toString(), npc: 500_000_000,
                msgs: [{ d: 1_000_000, type: 'note-on', chan: 0, note: 36, vel: 0.5 }] }
  expect(scheduleCycle(msg, c, 9.0).events[0].time).toBeCloseTo(10.001, 9)
})
