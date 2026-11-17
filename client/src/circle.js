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

// Cycle messages arrive about a cycle before they are heard, so the
// circle must not jump to a bar the moment it arrives. Each bar is
// queued with its start in audio seconds -- clock.toAudioTime(t0), which
// already includes the output latency -- and becomes current only when
// audio time reaches it. A null start means the clock is not ready
// (audio not started): such a bar is shown at once, with no playhead.
const MAX_QUEUED = 3

export function makeBarQueue () {
  let entries = []

  function current (now) {
    let idx = -1
    for (let i = 0; i < entries.length; i++) {
      const s = entries[i].start
      if (s === null || s <= now) idx = i
    }
    if (idx < 0) return null
    if (idx > 0) entries = entries.slice(idx)
    return entries[0]
  }

  return {
    push (msg, start) {
      entries.push({ msg, start, dur: msg.npc / 1e9 })
      // A null start supersedes everything before it; and a background
      // tab (no animation frames, so no current() calls) must not let
      // the queue grow without bound.
      if (start === null) entries = [entries[entries.length - 1]]
      if (entries.length > MAX_QUEUED) entries = entries.slice(-MAX_QUEUED)
    },
    current,
    angle (now) {
      const cur = current(now)
      if (!cur || cur.start === null) return null
      return Math.min(TAU, TAU * (now - cur.start) / cur.dur)
    },
    clear () { entries = [] },
    size () { return entries.length }
  }
}

// --- rendering ------------------------------------------------------
// Templates only: every decision was made in circleModel.

const LIT_WINDOW = 0.08            // radians past a dot that it stays lit
const LABEL_ROOM = 12              // viewBox margin outside the outer ring

const esc = s => String(s).replace(/[&<>"]/g,
  c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c])
const f = n => +n.toFixed(2)
const deg = a => f(a * 180 / Math.PI)

function radial (cls, a, r1, r2) {
  const s = Math.sin(a); const c = Math.cos(a)
  return `<line class="${cls}" x1="${f(r1 * s)}" y1="${f(-r1 * c)}" x2="${f(r2 * s)}" y2="${f(-r2 * c)}"/>`
}

function lit (dotAngle, angle) {
  if (angle === null) return false
  const d = angle - dotAngle
  return d >= 0 && d <= LIT_WINDOW
}

function ringSvg (ring, angle) {
  const cls = ['ring', ring.muted && 'muted', ring.error && 'error'].filter(Boolean).join(' ')
  const half = ring.width / 2
  const parts = [`<g class="${cls}">`,
    `<circle class="track" r="${f(ring.r)}"/>`]
  for (const a of ring.ticks) parts.push(radial('tick', a, ring.r - half * 0.3, ring.r + half * 0.3))
  for (const a of ring.spokes) parts.push(radial('spoke', a, ring.r - half * 0.8, ring.r + half * 0.8))
  for (const d of ring.dots) {
    const s = Math.sin(d.angle); const c = Math.cos(d.angle)
    parts.push(`<circle class="hit${lit(d.angle, angle) ? ' lit' : ''}" data-a="${f(d.angle)}" ` +
      `cx="${f(d.r * s)}" cy="${f(-d.r * c)}" r="${f(d.size)}">` +
      (d.label ? `<title>${esc(d.label)}</title>` : '') + '</circle>')
  }
  parts.push(`<text class="lane" x="-2" y="${f(-ring.r + 1.5)}">${esc(ring.label)}</text>`, '</g>')
  return parts.join('')
}

export function circleSvg (model, angle) {
  const o = f(model.outer + LABEL_ROOM)
  const head = angle === null
    ? ''
    : `<line class="playhead" x1="0" y1="${-f(R_INNER / 2)}" x2="0" y2="${-f(model.outer)}" transform="rotate(${deg(angle)})"/>`
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${-o} ${-o} ${2 * o} ${2 * o}">` +
    model.rings.map(r => ringSvg(r, angle)).join('') + head + '</svg>'
}

// One line per voice: its name and meter, with a mark when the meter
// was guessed rather than declared with :meter.
export function circleLegend (model) {
  const seen = new Set()
  const rows = []
  for (const r of model.rings) {
    if (seen.has(r.voice)) continue
    seen.add(r.voice)
    rows.push(`<li class="${r.muted ? 'muted' : ''}${r.error ? ' error' : ''}">` +
      `<span class="name">${esc(r.voice)}</span> <span class="meter">${esc(r.meterLabel)}` +
      `${r.inferred ? ' <em title="guessed — set :meter to fix">~</em>' : ''}</span></li>`)
  }
  return `<ul class="circle-legend">${rows.join('')}</ul>`
}

// Rebuild on a new bar; otherwise only move the playhead and the lit
// dots, so a phone is not re-laying out the whole SVG every frame.
export function renderCircle (el, model, angle) {
  if (el._circleModel !== model) {
    el.innerHTML = circleSvg(model, angle) + circleLegend(model)
    el._circleModel = model
    return
  }
  const svg = el.firstElementChild
  let head = svg.querySelector('.playhead')
  if (angle === null) { if (head) head.remove() } else {
    if (!head) {
      svg.insertAdjacentHTML('beforeend',
        `<line class="playhead" x1="0" y1="${-f(R_INNER / 2)}" x2="0" y2="${-f(model.outer)}"/>`)
      head = svg.querySelector('.playhead')
    }
    head.setAttribute('transform', `rotate(${deg(angle)})`)
  }
  for (const dot of svg.querySelectorAll('.hit')) {
    dot.classList.toggle('lit', lit(Number(dot.dataset.a), angle))
  }
}
