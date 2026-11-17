// The live circle view: each voice's current bar as a ring, with its
// meter drawn as spokes (beats) and ticks (subdivisions).
//
// Same split as hud.js: circleModel is pure and decides everything,
// the render layer below it is a template with no decisions in it.

import { meterAngles } from './geometry.js'
import { midiNoteName } from './hud.js'

export const R_INNER = 20
export const R_OUTER = 100
export const MIN_RING = 6
export const DOT_MIN = 1.5
export const DOT_MAX = 4

const TAU = 2 * Math.PI

function dot (hit, r, width, pitched) {
  const vel = Math.min(1, Math.max(0, hit.vel ?? 0.8))
  const size = Math.min(DOT_MIN + (DOT_MAX - DOT_MIN) * vel, width / 2)
  return { angle: TAU * hit.at, r, size, label: pitched ? midiNoteName(hit.note) : null }
}

export function circleModel (msg) {
  const shapes = msg.shapes || {}
  const voices = msg.voices || {}

  // One entry per ring before layout: [voice, lane|null, shape].
  const slots = []
  for (const name of Object.keys(shapes).sort()) {
    const s = shapes[name]
    if (!s || !s.kind) continue
    if (s.kind === 'drum') for (const lane of s.lanes) slots.push([name, lane, s])
    else slots.push([name, null, s])
  }

  // Rings share the band between R_INNER and R_OUTER; past MIN_RING each
  // they push outward instead of overlapping, and `outer` tells the
  // renderer how big to make the viewBox.
  const width = slots.length ? Math.max(MIN_RING, (R_OUTER - R_INNER) / slots.length) : 0
  const outer = Math.max(R_OUTER, R_INNER + width * slots.length)

  const rings = slots.map(([name, lane, s], i) => {
    const r = R_INNER + width * (i + 0.5)
    const v = voices[name] || {}
    const { spokes, ticks } = meterAngles(s.meter)
    const hits = lane === null ? s.hits : s.hits.filter(h => h.drum === lane)
    return {
      voice: name,
      lane,
      label: lane ?? name,
      r,
      width,
      muted: !!v.muted,
      error: !!v.error,
      meterLabel: s.meter.join('+'),
      inferred: s.source === 'inferred',
      spokes,
      ticks,
      dots: hits.map(h => dot(h, r, width, lane === null))
    }
  })

  return { rings, outer }
}
