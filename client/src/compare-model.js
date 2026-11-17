// The compare page's logic: meters overlaid on a 60-step circle.
//
// 60 divides by 2, 3, 4, 5, 6, 10, 12, 15, 20 and 30, so 4/4, 3/4, 6/8,
// 5/4 and 12/8 all land exactly on one grid -- the way minutes do on a
// clock. A meter whose total does not divide 60 (7/8) is still drawn at
// its true angles, just off the ticks. Pure; compare.js is the DOM side.

import { meterAngles, polar, polygonPoints } from './geometry.js'

export const MAX_RINGS = 4
const TAU = 2 * Math.PI

// name -> the rings it adds. Polyrhythms add two.
export const PRESETS = {
  '4/4': [[1, 1, 1, 1]],
  '3/4': [[1, 1, 1]],
  '6/8': [[3, 3]],
  '5/4': [[1, 1, 1, 1, 1]],
  '12/8': [[3, 3, 3, 3]],
  '7/8': [[2, 2, 3]],
  '3:2': [[1, 1, 1], [1, 1]],
  '4:3': [[1, 1, 1, 1], [1, 1, 1]]
}

const total = g => g.reduce((a, b) => a + b, 0)
const round2 = n => Math.round(n * 100) / 100
const isPulse = g => g.every(x => x === 1)

export function parseGrouping (text) {
  const t = String(text).trim()
  if (PRESETS[t]) return PRESETS[t].length === 1 ? [...PRESETS[t][0]] : null
  if (!/^\d+(\s*\+\s*\d+)*$/.test(t)) return null
  const g = t.split('+').map(s => Number(s.trim()))
  return g.every(n => n > 0) ? g : null
}

export function onSixtyGrid (grouping) {
  return 60 % total(grouping) === 0
}

// Where each beat (group start) falls, in minutes of the 60-step face.
export function beatMinutes (grouping) {
  const n = total(grouping)
  const out = []
  let at = 0
  for (const g of grouping) { out.push(round2(60 * at / n)); at += g }
  return out
}

export function sharedMinutes (groupings) {
  const hits = new Map()
  for (const g of groupings) {
    for (const m of new Set(beatMinutes(g))) hits.set(m, (hits.get(m) || 0) + 1)
  }
  return [...hits].filter(([, c]) => c >= 2).map(([m]) => m).sort((a, b) => a - b)
}

// "3 against 4" when every ring is a plain pulse, names otherwise.
export function caption (names, groupings) {
  if (groupings.length < 2) return ''
  const who = groupings.every(isPulse)
    ? groupings.map(g => g.length).join(' against ')
    : names.join(' and ')
  return `${who}: together at ${sharedMinutes(groupings).join(', ')}`
}

// Add a preset's or a typed grouping's rings; null when it would pass
// MAX_RINGS, so the caller can say why nothing happened.
export function addRings (rings, name, groupings) {
  if (rings.length + groupings.length > MAX_RINGS) return null
  const label = (g, i) => groupings.length > 1 ? `${g.length} (${name})` : name
  return [...rings, ...groupings.map((g, i) => ({ name: label(g, i), grouping: g, muted: false }))]
}

// --- face ------------------------------------------------------------

const FACE_R = 100
const RING_R0 = 84
const RING_STEP = 18
const f = n => +n.toFixed(2)

function line (cls, a, r1, r2) {
  const p = polar(a, r1); const q = polar(a, r2)
  return `<line class="${cls}" x1="${f(p.x)}" y1="${f(p.y)}" x2="${f(q.x)}" y2="${f(q.y)}"/>`
}

export function ringRadius (i) { return RING_R0 - i * RING_STEP }

export function faceSvg (rings, angle) {
  const parts = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="-122 -122 244 244">',
    `<circle class="face" r="${FACE_R}"/>`]
  for (let m = 0; m < 60; m++) {
    const long = m % 5 === 0
    parts.push(line(long ? 'face-tick long' : 'face-tick', TAU * m / 60, FACE_R, FACE_R - (long ? 9 : 4)))
    if (long) {
      const p = polar(TAU * m / 60, FACE_R + 11)
      parts.push(`<text class="face-label" x="${f(p.x)}" y="${f(p.y)}">${m}</text>`)
    }
  }
  for (const m of sharedMinutes(rings.map(r => r.grouping))) {
    const p = polar(TAU * m / 60, FACE_R)
    parts.push(`<circle class="shared" cx="${f(p.x)}" cy="${f(p.y)}" r="4"/>`)
  }
  rings.forEach((ring, i) => {
    const r = ringRadius(i)
    const { spokes, ticks } = meterAngles(ring.grouping)
    parts.push(`<g class="cring c${i}${ring.muted ? ' muted' : ''}">`, `<circle class="track" r="${r}"/>`)
    for (const a of ticks) parts.push(line('tick', a, r - 2, r + 2))
    const pts = polygonPoints(spokes, r).map(p => `${f(p.x)},${f(p.y)}`).join(' ')
    if (spokes.length >= 3) parts.push(`<polygon class="poly" points="${pts}"/>`)
    else if (spokes.length === 2) parts.push(`<polyline class="poly-line" points="${pts}"/>`)
    for (const a of spokes) {
      const p = polar(a, r)
      parts.push(`<circle class="beat" cx="${f(p.x)}" cy="${f(p.y)}" r="3"/>`)
    }
    parts.push('</g>')
  })
  if (angle !== null) {
    parts.push(`<line class="playhead" x1="0" y1="0" x2="0" y2="${-FACE_R}" transform="rotate(${f(angle * 180 / Math.PI)})"/>`)
  }
  parts.push('</svg>')
  return parts.join('')
}
