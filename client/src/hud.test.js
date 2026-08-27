import { expect, test } from 'vitest'
import { connectionState, hudModel, midiNoteName } from './hud.js'

const msg = {
  t: 'cycle', n: 12, t0: '1000', npc: 500_000_000, bpm: 140,
  voices: {
    lead: { chan: 1, muted: true, soloed: false, error: null },
    bass: { chan: 0, muted: false, soloed: false, error: 'boom' }
  },
  msgs: [
    { d: 0, type: 'note-on', chan: 0, note: 36, vel: 0.8 },
    { d: 1, type: 'note-off', chan: 0, note: 36 }
  ]
}

test('note numbers become mu note names', () => {
  expect(midiNoteName(60)).toBe('C4')
  expect(midiNoteName(36)).toBe('C2')
  expect(midiNoteName(39)).toBe('Eb2')
  expect(midiNoteName(0)).toBe('C-1')
})

test('the model carries cycle, tempo and transport state', () => {
  const m = hudModel(msg, null)
  expect(m.cycle).toBe(12)
  expect(m.bpm).toBe(140)
})

test('voices are sorted by name so the list does not jump', () => {
  expect(hudModel(msg, null).voices.map(v => v.name)).toEqual(['bass', 'lead'])
})

test('mute, solo and error ride through per voice', () => {
  const [bass, lead] = hudModel(msg, null).voices
  expect(bass.error).toBe('boom')
  expect(lead.muted).toBe(true)
})

test('only note-ons are listed as events, named', () => {
  const m = hudModel(msg, null)
  expect(m.events).toEqual([{ note: 36, name: 'C2', chan: 0, type: 'note-on' }])
})

test('a skipped cycle number counts as a drop', () => {
  const first = hudModel({ ...msg, n: 1 }, null)
  expect(first.drops).toBe(0)
  const second = hudModel({ ...msg, n: 5 }, first)
  expect(second.drops).toBe(1)
})

test('consecutive cycles do not count as drops', () => {
  const a = hudModel({ ...msg, n: 1 }, null)
  const b = hudModel({ ...msg, n: 2 }, a)
  expect(b.drops).toBe(0)
})

test('a closed socket reads as disconnected', () => {
  expect(connectionState({ socketOpen: false, lastCycleAt: 100, now: 100 }))
    .toBe('disconnected')
})

test('an open socket that has never delivered a cycle reads as stopped', () => {
  expect(connectionState({ socketOpen: true, lastCycleAt: null, now: 100 }))
    .toBe('stopped')
})

test('an open socket delivering cycles reads as playing', () => {
  expect(connectionState({ socketOpen: true, lastCycleAt: 99.9, now: 100 }))
    .toBe('playing')
})

test('an open socket gone quiet reads as stopped, not disconnected', () => {
  // (end!) or (hush) leaves the socket up and the cycles stop. Reporting
  // that as a lost connection would send the performer chasing the wrong
  // problem mid-set.
  expect(connectionState({ socketOpen: true, lastCycleAt: 95, now: 100 }))
    .toBe('stopped')
})
