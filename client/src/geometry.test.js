import { expect, test } from 'vitest'
import { meterAngles, polar, polygonPoints } from './geometry.js'

const r6 = xs => xs.map(a => +a.toFixed(6))
const TAU = 2 * Math.PI

test('6/8 and 3/4 have the same ticks but different spokes', () => {
  expect(meterAngles([3, 3]).spokes).toEqual([0, Math.PI])
  expect(r6(meterAngles([2, 2, 2]).spokes)).toEqual(r6([0, TAU / 3, 2 * TAU / 3]))
  expect(meterAngles([3, 3]).ticks).toHaveLength(6)
  expect(meterAngles([2, 2, 2]).ticks).toHaveLength(6)
  expect(meterAngles([3, 3]).steps).toBe(6)
})

test('7/8 as 2+2+3 has uneven spokes', () => {
  expect(r6(meterAngles([2, 2, 3]).spokes.map(a => a / TAU))).toEqual(r6([0, 2 / 7, 4 / 7]))
})

test('ticks are evenly spaced around the whole circle', () => {
  const { ticks } = meterAngles([4, 4, 4, 4])
  expect(ticks).toHaveLength(16)
  expect(ticks[0]).toBe(0)
  expect(+(ticks[4]).toFixed(6)).toBe(+(Math.PI / 2).toFixed(6))
})

test("angle 0 is twelve o'clock and angles run clockwise", () => {
  expect(polar(0, 10)).toEqual({ x: 0, y: -10 })
  const q = polar(Math.PI / 2, 10)
  expect(q.x).toBeCloseTo(10)
  expect(q.y).toBeCloseTo(0)
})

test('polygon points sit on the circle at the given angles', () => {
  const pts = polygonPoints([0, Math.PI], 5)
  expect(pts).toHaveLength(2)
  expect(pts[0]).toEqual({ x: 0, y: -5 })
  expect(pts[1].y).toBeCloseTo(5)
})
