import { expect, test } from 'vitest'
import { circleModel, DOT_MAX, DOT_MIN, MIN_RING, R_INNER } from './circle.js'

const drums = {
  meter: [2, 2, 2, 2], source: 'inferred', kind: 'drum', lanes: ['bd', 'sn', 'hh'],
  hits: [
    { at: 0, drum: 'bd', note: 36, vel: 0.8 },
    { at: 0.5, drum: 'sn', note: 38, vel: 1 },
    { at: 0.75, drum: 'hh', note: 42, vel: 0 }
  ]
}
const bass = {
  meter: [3, 3], source: 'declared', kind: 'pitched', lanes: [],
  hits: [{ at: 0, note: 60, vel: 0.8 }]
}
const msg = (shapes, voices = {}) => ({ t: 'cycle', n: 1, t0: '0', npc: 1e9, voices, shapes })

test('voices sorted by name, drum voice gets a ring per lane in wire order', () => {
  const m = circleModel(msg({ drums, bass }))
  expect(m.rings.map(r => [r.voice, r.lane])).toEqual(
    [['bass', null], ['drums', 'bd'], ['drums', 'sn'], ['drums', 'hh']])
  expect(m.rings[0].r).toBeLessThan(m.rings[1].r)
})

test('a resting lane keeps its ring', () => {
  const resting = { ...drums, hits: [drums.hits[0]] }
  const m = circleModel(msg({ drums: resting }))
  expect(m.rings.map(r => r.lane)).toEqual(['bd', 'sn', 'hh'])
  expect(m.rings[1].dots).toEqual([])
})

test('each lane only carries its own hits, at 2π·at', () => {
  const m = circleModel(msg({ drums }))
  const sn = m.rings.find(r => r.lane === 'sn')
  expect(sn.dots).toHaveLength(1)
  expect(sn.dots[0].angle).toBeCloseTo(Math.PI)
  expect(sn.dots[0].r).toBe(sn.r)
})

test('dot size scales with velocity', () => {
  const m = circleModel(msg({ drums }))
  expect(m.rings.find(r => r.lane === 'sn').dots[0].size).toBe(DOT_MAX)
  expect(m.rings.find(r => r.lane === 'hh').dots[0].size).toBe(DOT_MIN)
})

test('meter label and inferred flag come from the shape', () => {
  const m = circleModel(msg({ drums, bass }))
  expect(m.rings[0].meterLabel).toBe('3+3')
  expect(m.rings[0].inferred).toBe(false)
  expect(m.rings[1].meterLabel).toBe('2+2+2+2')
  expect(m.rings[1].inferred).toBe(true)
  expect(m.rings[0].spokes).toHaveLength(2)
  expect(m.rings[0].ticks).toHaveLength(6)
})

test('muted and error flags come from msg.voices', () => {
  const m = circleModel(msg({ drums, bass }, {
    drums: { chan: 9, muted: true, soloed: false, error: null },
    bass: { chan: 0, muted: false, soloed: false, error: 'boom' }
  }))
  expect(m.rings[0]).toMatchObject({ voice: 'bass', muted: false, error: true })
  expect(m.rings[1]).toMatchObject({ voice: 'drums', muted: true, error: false })
})

test('a null shape draws no ring but others remain', () => {
  const m = circleModel(msg({ bad: null, bass }))
  expect(m.rings.map(r => r.voice)).toEqual(['bass'])
})

test('a voice that has never had a hit draws no ring', () => {
  const m = circleModel(msg({ quiet: { ...bass, kind: null, hits: [] }, bass }))
  expect(m.rings.map(r => r.voice)).toEqual(['bass'])
})

test('no shapes at all is an empty model, not a crash', () => {
  expect(circleModel(msg(undefined)).rings).toEqual([])
  expect(circleModel(msg({})).rings).toEqual([])
})

test('many rings shrink but never below MIN_RING, and never overlap', () => {
  const lanes = Array.from({ length: 20 }, (_, i) => `d${i}`)
  const m = circleModel(msg({ big: { ...drums, lanes, hits: [] } }))
  expect(m.rings).toHaveLength(20)
  m.rings.forEach(r => expect(r.width).toBeGreaterThanOrEqual(MIN_RING))
  for (let i = 1; i < m.rings.length; i++) {
    expect(m.rings[i].r - m.rings[i - 1].r).toBeGreaterThanOrEqual(m.rings[i].width - 1e-9)
  }
  expect(m.rings[0].r - m.rings[0].width / 2).toBeGreaterThanOrEqual(R_INNER - 1e-9)
  expect(m.outer).toBeGreaterThanOrEqual(m.rings[19].r + m.rings[19].width / 2 - 1e-9)
})

test('pitched dots carry note names', () => {
  const m = circleModel(msg({ bass }))
  expect(m.rings[0].dots[0].label).toBe('C4')
})
