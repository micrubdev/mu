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
      if (depth === 0) {
        // Walk backwards over reader prefixes: #, _, ', `, ~, @, ^
        let prefixStart = i
        while (prefixStart > 0 && '#_\'`~@^'.includes(text[prefixStart - 1])) {
          prefixStart--
        }
        // Delimiter guard: prefix run must be preceded by whitespace, bracket,
        // or buffer start. Otherwise it's the tail of a preceding symbol.
        const charBefore = prefixStart > 0 ? text[prefixStart - 1] : null
        const isValidDelimiter = charBefore === null || /\s|[(){}\[\]]/.test(charBefore)
        // If leftmost char of run is bare `_`, reject (only meaningful in `#_`)
        const runStart = text[prefixStart]
        const isBarePrefixUnderscore = runStart === '_' && !text.slice(prefixStart, i).includes('#')
        start = (isValidDelimiter && !isBarePrefixUnderscore) ? prefixStart : i
      }
      depth++
    } else if (ch === ')' || ch === ']' || ch === '}') {
      if (depth > 0) {
        depth--
        if (depth === 0 && start !== null) {
          if (pos >= start && pos <= i + 1) return { from: start, to: i + 1 }
          start = null
        }
      }
    }
    i++
  }
  return null
}

// Find the namespace declaration in text, reader-aware.
// Returns the namespace name or 'jam' as default.
export function findNamespace (text) {
  const pattern = /\(ns\s+([\w.\-*+!?<>]+)/g
  let match
  while ((match = pattern.exec(text)) !== null) {
    const idx = match.index
    // Only accept if this (ns ...) is a top-level form
    if (topLevelFormAt(text, idx)?.from === idx) {
      return match[1]
    }
  }
  return 'jam'
}

// Register vim actions once at module scope
Vim.defineAction('muEvalTopLevel', (cm) => {
  // Will be called with cm.cm6 bound to the view
  // The actual evaluation happens in makeEditor
  return true
})
Vim.mapCommand(',e', 'action', 'muEvalTopLevel', {}, { context: 'normal' })

export function makeEditor (parent, { onEval, doc = '' }) {
  const evalTopLevel = view => {
    const text = view.state.doc.toString()
    const range = topLevelFormAt(text, view.state.selection.main.head)
    if (range) onEval(text.slice(range.from, range.to))
    return true
  }
  const evalBuffer = view => { onEval(view.state.doc.toString()); return true }

  // Update the vim action to use this view's evalTopLevel
  Vim.defineAction('muEvalTopLevel', (cm) => evalTopLevel(cm.cm6))

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
      return findNamespace(view.state.doc.toString())
    }
  }
}
