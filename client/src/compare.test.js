import { expect, test } from 'vitest'
import {
  addRings, beatMinutes, caption, faceSvg, MAX_RINGS, onSixtyGrid,
  parseGrouping, PRESETS, sharedMinutes
} from './compare-model.js'

const count = (s, needle) => s.split(needle).length - 1

test('presets on the 60 grid', () => {
  expect(beatMinutes([1, 1, 1, 1])).toEqual([0, 15, 30, 45])
  expect(beatMinutes([1, 1, 1])).toEqual([0, 20, 40])
  expect(beatMinutes([3, 3])).toEqual([0, 30])
  expect(beatMinutes([1, 1, 1, 1, 1])).toEqual([0, 12, 24, 36, 48])
  expect(beatMinutes([3, 3, 3, 3])).toEqual([0, 15, 30, 45])
})

test('off-grid beats keep their true position', () => {
  expect(beatMinutes([2, 2, 3])).toEqual([0, 17.14, 34.29])
})

test('3 against 4 meet only at 0', () => {
  expect(sharedMinutes([[1, 1, 1], [1, 1, 1, 1]])).toEqual([0])
})

test('4/4 and 6/8 share 0 and 30', () => {
  expect(sharedMinutes([[1, 1, 1, 1], [3, 3]])).toEqual([0, 30])
})

test('one ring shares nothing', () => {
  expect(sharedMinutes([[1, 1, 1, 1]])).toEqual([])
})

test('7/8 is off the 60 grid, the presets in the table are on it', () => {
  expect(onSixtyGrid([2, 2, 3])).toBe(false)
  for (const g of [[1, 1, 1, 1], [1, 1, 1], [3, 3], [1, 1, 1, 1, 1], [3, 3, 3, 3]]) {
    expect(onSixtyGrid(g)).toBe(true)
  }
})

test('parseGrouping', () => {
  expect(parseGrouping('2+2+3')).toEqual([2, 2, 3])
  expect(parseGrouping(' 3 + 3 ')).toEqual([3, 3])
  expect(parseGrouping('6/8')).toEqual([3, 3])
  expect(parseGrouping('2+x')).toBeNull()
  expect(parseGrouping('0+3')).toBeNull()
  expect(parseGrouping('')).toBeNull()
  expect(parseGrouping('3:2')).toBeNull()
})

test('polyrhythm presets add two rings', () => {
  expect(PRESETS['3:2']).toEqual([[1, 1, 1], [1, 1]])
  expect(PRESETS['4:3']).toEqual([[1, 1, 1, 1], [1, 1, 1]])
})

test('caption names shared minutes', () => {
  expect(caption(['3/4', '4/4'], [[1, 1, 1], [1, 1, 1, 1]])).toBe('3 against 4: together at 0')
  expect(caption(['4/4', '6/8'], [[1, 1, 1, 1], [3, 3]])).toBe('4/4 and 6/8: together at 0, 30')
  expect(caption(['4/4'], [[1, 1, 1, 1]])).toBe('')
})

test('a fifth ring is refused', () => {
  let rings = []
  for (let i = 0; i < MAX_RINGS; i++) rings = addRings(rings, `r${i}`, [[1, 1]])
  expect(rings).toHaveLength(4)
  expect(addRings(rings, 'r5', [[1, 1, 1]])).toBeNull()
  expect(addRings(rings.slice(0, 3), '3:2', PRESETS['3:2'])).toBeNull()
})

test('the face has 60 ticks, 12 labels, a polygon per ring and the shared highlights', () => {
  const rings = addRings([], '3:2', [[1, 1, 1], [1, 1, 1, 1]])
  const svg = faceSvg(rings, null)
  expect(count(svg, 'class="face-tick')).toBe(60)
  expect(count(svg, 'class="face-label"')).toBe(12)
  expect(count(svg, 'class="poly"')).toBe(2)
  expect(count(svg, 'class="shared"')).toBe(1)
  expect(svg).not.toContain('class="playhead"')
  expect(faceSvg(rings, Math.PI)).toContain('rotate(180)')
})

test('parseGrouping refuses more than 64 steps, so a typo cannot hang the tab', () => {
  expect(parseGrouping('99999999')).toBeNull()
  expect(parseGrouping('32+33')).toBeNull()
  expect(parseGrouping('64')).toEqual([64])
})
