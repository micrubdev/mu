// The compare page's DOM and sound. Every decision about what to draw
// lives in compare-model.js; this file wires it to buttons and clicks.

import { addRings, caption, faceSvg, onSixtyGrid, parseGrouping, PRESETS } from './compare-model.js'
import './compare.css'

const $ = id => document.getElementById(id)
const PITCHES = [880, 660, 440, 330]     // one per ring, by index
const LOOKAHEAD = 0.15                   // seconds of clicks scheduled ahead

const state = {
  rings: addRings([], '3:4', [[1, 1, 1], [1, 1, 1, 1]]).map((r, i) => ({ ...r, name: ['3/4', '4/4'][i] })),
  barSec: 4,
  playing: false,
  t0: 0,
  scheduledUntil: 0
}

let audio = null

function say (text) { $('message').textContent = text }

function drawStatic () {
  $('face').innerHTML = faceSvg(state.rings, state.playing ? 0 : null)
  $('caption').textContent = caption(state.rings.map(r => r.name), state.rings.map(r => r.grouping)) ||
    (state.rings.length ? 'Add another meter to compare.' : 'Add a meter to start.')
  $('rings').innerHTML = state.rings.map((r, i) => `
    <li class="c${i}${r.muted ? ' muted' : ''}">
      <span class="swatch"></span>
      <span class="name">${r.name}</span>
      <span class="grouping">${r.grouping.join('+')}</span>
      ${onSixtyGrid(r.grouping) ? '' : '<span class="off">off the 60 grid</span>'}
      <button type="button" data-mute="${i}">${r.muted ? 'unmute' : 'mute'}</button>
      <button type="button" data-remove="${i}" aria-label="remove ${r.name}">✕</button>
    </li>`).join('')
}

function add (name, groupings) {
  const next = addRings(state.rings, name, groupings)
  if (!next) { say('Up to 4 meters at once. Remove one first.'); return }
  say('')
  state.rings = next
  drawStatic()
}

// --- sound -------------------------------------------------------------

function blip (t, freq, accent) {
  const osc = audio.createOscillator()
  const gain = audio.createGain()
  osc.frequency.value = freq
  gain.gain.setValueAtTime(accent ? 0.35 : 0.2, t)
  gain.gain.exponentialRampToValueAtTime(0.001, t + 0.03)
  osc.connect(gain).connect(audio.destination)
  osc.start(t)
  osc.stop(t + 0.04)
  queued.push({ osc, t })
}

// Clicks already handed to Web Audio but not yet sounded. Kept so a
// bar-length change can cancel them and reschedule on the new timing.
let queued = []

function cancelQueued () {
  const now = audio.currentTime
  for (const { osc, t } of queued) if (t > now) osc.stop()
  queued = []
}

function schedule () {
  if (!state.playing) return
  const from = Math.max(state.scheduledUntil, audio.currentTime)
  const to = audio.currentTime + LOOKAHEAD
  const bar = state.barSec
  for (let b = Math.floor((from - state.t0) / bar); b <= Math.floor((to - state.t0) / bar); b++) {
    state.rings.forEach((ring, i) => {
      if (ring.muted) return
      const n = ring.grouping.reduce((a, c) => a + c, 0)
      let at = 0
      for (const g of ring.grouping) {
        const t = state.t0 + (b + at / n) * bar
        if (t >= from && t < to) blip(t, PITCHES[i], at === 0)
        at += g
      }
    })
  }
  state.scheduledUntil = to
  const now = audio.currentTime
  queued = queued.filter(q => q.t > now)
}

function frame () {
  if (!state.playing) return
  const phase = (((audio.currentTime - state.t0) / state.barSec) % 1 + 1) % 1
  const head = $('face').querySelector('.playhead')
  if (head) head.setAttribute('transform', `rotate(${(phase * 360).toFixed(2)})`)
  requestAnimationFrame(frame)
}

let timer = null
async function play () {
  state.playing = true          // before the await: a double tap starts once
  audio ??= new AudioContext()
  await audio.resume()
  if (!state.playing) return    // stopped again while audio was waking up
  clearInterval(timer)
  state.t0 = audio.currentTime + 0.05
  state.scheduledUntil = state.t0
  $('play').textContent = 'stop'
  drawStatic()
  timer = setInterval(schedule, 25)
  requestAnimationFrame(frame)
}

function stop () {
  state.playing = false
  clearInterval(timer)
  if (audio) cancelQueued()
  $('play').textContent = 'play'
  drawStatic()
}

// --- wiring --------------------------------------------------------------

$('presets').innerHTML = Object.keys(PRESETS)
  .map(name => `<button type="button" data-preset="${name}">${name}</button>`).join('')

$('presets').addEventListener('click', e => {
  const name = e.target.dataset?.preset
  if (name) add(name, PRESETS[name])
})

$('custom-form').addEventListener('submit', e => {
  e.preventDefault()
  const text = $('custom').value
  const g = parseGrouping(text)
  if (!g) { say('Could not read that. Write groups joined by +, like 2+2+3.'); return }
  add(text.replace(/\s+/g, ''), [g])
  $('custom').value = ''
})

$('rings').addEventListener('click', e => {
  const { mute, remove } = e.target.dataset || {}
  if (mute !== undefined) state.rings[mute].muted = !state.rings[mute].muted
  else if (remove !== undefined) state.rings.splice(Number(remove), 1)
  else return
  say('')
  drawStatic()
})

$('play').addEventListener('click', () => (state.playing ? stop() : play()))

$('bar').addEventListener('input', () => {
  const next = Number($('bar').value)
  if (state.playing) {
    // Keep the playhead where it is: re-anchor so the phase carries over.
    const now = audio.currentTime
    const phase = (((now - state.t0) / state.barSec) % 1 + 1) % 1
    state.t0 = now - phase * next
    // Drop clicks queued on the old timing and reschedule from now, so a
    // drag never doubles or skips a beat.
    cancelQueued()
    state.scheduledUntil = now
  }
  state.barSec = next
  $('bar-value').textContent = `${next} s`
})

drawStatic()
