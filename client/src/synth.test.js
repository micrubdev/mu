import { expect, test } from 'vitest'
import { toMidiEvents } from './synth.js'

// Audio time 10.0s is "now"; the synth's own clock is in ms from 0.
const conv = t => (t - 10.0) * 1000

test('a note-on becomes a timed midi event with 0-127 velocity', () => {
  const out = toMidiEvents(
    [{ time: 10.5, type: 'note-on', chan: 0, note: 36, vel: 0.8 }], conv)
  expect(out).toEqual([{ atMs: 500, type: 'note-on', chan: 0, note: 36, vel127: 102 }])
})

test('velocity is clamped into range', () => {
  const out = toMidiEvents([
    { time: 10.0, type: 'note-on', chan: 0, note: 36, vel: 5 },
    { time: 10.0, type: 'note-on', chan: 0, note: 37, vel: -1 }
  ], conv)
  expect(out.map(e => e.vel127)).toEqual([127, 0])
})

test('a note-off carries no velocity', () => {
  const out = toMidiEvents(
    [{ time: 10.25, type: 'note-off', chan: 0, note: 36 }], conv)
  expect(out).toEqual([{ atMs: 250, type: 'note-off', chan: 0, note: 36 }])
})

test('an event already in the past is pulled to zero rather than dropped', () => {
  // A few ms of lateness inside a cycle is normal; the cycle-level drop in
  // schedule.js is what handles real lateness. Playing it immediately is
  // better than losing a note-off and hanging the voice.
  const out = toMidiEvents(
    [{ time: 9.9, type: 'note-off', chan: 0, note: 36 }], conv)
  expect(out[0].atMs).toBe(0)
})

test('order is preserved', () => {
  const out = toMidiEvents([
    { time: 10.0, type: 'note-on', chan: 0, note: 36, vel: 0.5 },
    { time: 10.1, type: 'note-off', chan: 0, note: 36 }
  ], conv)
  expect(out.map(e => e.atMs)).toEqual([0, 100])
})
