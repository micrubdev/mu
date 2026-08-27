// Flat names, matching mu's notation: it writes eb2, not d#2.
const NAMES = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B']

export function midiNoteName (n) {
  return NAMES[n % 12] + (Math.floor(n / 12) - 1)
}

// The view model. Pure, so the display logic is testable and the DOM
// layer stays a template with no decisions in it.
export function hudModel (msg, previous) {
  const skipped = previous && msg.n !== previous.cycle + 1
  return {
    cycle: msg.n,
    bpm: msg.bpm,
    playing: true,
    drops: (previous ? previous.drops : 0) + (skipped ? 1 : 0),
    voices: Object.entries(msg.voices || {})
      .map(([name, v]) => ({ name, chan: v.chan, muted: v.muted,
                             soloed: v.soloed, error: v.error }))
      .sort((a, b) => a.name.localeCompare(b.name)),
    events: (msg.msgs || [])
      .filter(m => m.type === 'note-on')
      .map(m => ({ note: m.note, name: midiNoteName(m.note),
                   chan: m.chan, type: m.type }))
  }
}

// Three states, not two. An open socket that has gone quiet means the
// transport stopped -- (end!), (hush), or a begin! that never happened.
// Showing that as "disconnected" would send a performer chasing the
// network when the answer is one form in the editor.
const QUIET_MS = 2000

export function connectionState ({ socketOpen, lastCycleAt, now }) {
  if (!socketOpen) return 'disconnected'
  if (lastCycleAt === null) return 'stopped'
  return (now - lastCycleAt) * 1000 > QUIET_MS ? 'stopped' : 'playing'
}

export function renderHud (el, model, state) {
  el.innerHTML = `
    <div class="transport ${state}">${state} · cycle ${model.cycle} · ${
      model.bpm ?? '--'} bpm${model.drops ? ` · ${model.drops} dropped` : ''}</div>
    <ul class="voices">${model.voices.map(v => `
      <li class="${v.error ? 'error' : ''}${v.muted ? ' muted' : ''}${v.soloed ? ' solo' : ''}">
        ${v.name} <span class="chan">ch${v.chan}</span>
        ${v.error ? `<span class="err">${v.error}</span>` : ''}
      </li>`).join('')}</ul>
    <div class="events">${model.events.map(e => e.name).join(' ')}</div>`
}
