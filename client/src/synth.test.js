import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { makeSynth, toMidiEvents } from './synth.js'

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

// makeSynth's start() error path: this is pure control flow (guard state,
// fetch-status check), testable with a stub audioContext and a stubbed
// global fetch -- no real AudioContext/AudioWorklet needed. That machinery
// stays out of scope here; see synth.js's own doc comment and Task 8.
function fakeAudioContext () {
  return {
    resume: vi.fn(async () => {}),
    audioWorklet: { addModule: vi.fn(async () => {}) },
    sampleRate: 44100,
    destination: {},
    currentTime: 0
  }
}

// A real fetch() Response still has a working body reader on a 404 -- the
// body is just the error page's bytes. The stub matches that, so a test
// that removes the res.ok check doesn't accidentally pass for the wrong
// reason (a stub lacking arrayBuffer() throwing its own unrelated error).
function fake404Response () {
  return { ok: false, status: 404, arrayBuffer: vi.fn(async () => new ArrayBuffer(0)) }
}

let fetchMock

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

test('a 404 soundfont response makes start() reject before touching the worklet, and ready() stays false', async () => {
  fetchMock.mockResolvedValue(fake404Response())
  const audioContext = fakeAudioContext()
  const synth = makeSynth(audioContext, { soundfontUrl: '/gm.sf2' })

  await expect(synth.start()).rejects.toThrow(/404/)
  expect(synth.ready()).toBe(false)
  // Pins the failure to the res.ok check specifically, not to some later
  // step failing incidentally: nothing worklet-related should ever run.
  expect(audioContext.audioWorklet.addModule).not.toHaveBeenCalled()
})

test('a second start() after a failed one actually retries rather than no-opping', async () => {
  fetchMock.mockResolvedValue(fake404Response())
  const synth = makeSynth(fakeAudioContext(), { soundfontUrl: '/gm.sf2' })

  await expect(synth.start()).rejects.toThrow()
  await expect(synth.start()).rejects.toThrow()
  // If a failed start() left a truthy synth behind, the second call would
  // short-circuit on `if (synth) return` and resolve without ever calling
  // fetch again.
  expect(fetchMock).toHaveBeenCalledTimes(2)
})
