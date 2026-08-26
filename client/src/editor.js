import { clojure } from '@nextjournal/lang-clojure'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap } from '@codemirror/view'
import { Vim, vim } from '@replit/codemirror-vim'

// Find the top-level form containing pos by scanning depth from the start
// of the text. A reader-aware scan rather than a regex: strings, character
// literals and comments all contain parens that must not count.
export function topLevelFormAt (text, pos) {
  let depth = 0
  let start = null
  let i = 0
  while (i < text.length) {
    const ch = text[i]
    if (ch === '\\') { i += 2; continue }
    if (ch === ';') { while (i < text.length && text[i] !== '\n') i++; continue }
    if (ch === '"') {
      i++
      while (i < text.length && text[i] !== '"') { if (text[i] === '\\') i++; i++ }
      i++
      continue
    }
    if (ch === '(' || ch === '[' || ch === '{') {
      if (depth === 0) start = i
      depth++
    } else if (ch === ')' || ch === ']' || ch === '}') {
      depth--
      if (depth === 0 && start !== null) {
        if (pos >= start && pos <= i + 1) return { from: start, to: i + 1 }
        start = null
      }
    }
    i++
  }
  return null
}

export function makeEditor (parent, { onEval, doc = '' }) {
  const evalTopLevel = view => {
    const text = view.state.doc.toString()
    const range = topLevelFormAt(text, view.state.selection.main.head)
    if (range) onEval(text.slice(range.from, range.to))
    return true
  }
  const evalBuffer = view => { onEval(view.state.doc.toString()); return true }

  // ,e in normal mode, for hands that already live in vim.
  Vim.defineAction('muEvalTopLevel', (cm) => evalTopLevel(cm.cm6))
  Vim.mapCommand(',e', 'action', 'muEvalTopLevel', {}, { context: 'normal' })

  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [
        vim(),                     // must come before other keymaps
        clojure(),
        history(),
        keymap.of([
          { key: 'Ctrl-Enter', run: evalTopLevel },
          { key: 'Ctrl-Shift-Enter', run: evalBuffer },
          ...historyKeymap,
          ...defaultKeymap
        ])
      ]
    })
  })

  return {
    view,
    // The ns to eval in: whatever the buffer declares, else jam.
    currentNs () {
      const m = view.state.doc.toString().match(/\(ns\s+([\w.\-*+!?<>]+)/)
      return m ? m[1] : 'jam'
    }
  }
}
