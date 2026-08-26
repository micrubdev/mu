import { expect, test } from 'vitest'
import { topLevelFormAt, findNamespace } from './editor.js'

const src = `(ns jam)\n\n(def bass\n  (notes c2 eb2))\n\n(play! :bass #'bass)\n`

test('finds the form the cursor sits inside', () => {
  const pos = src.indexOf('notes')
  const { from, to } = topLevelFormAt(src, pos)
  expect(src.slice(from, to)).toBe('(def bass\n  (notes c2 eb2))')
})

test('finds the form when the cursor is on its opening paren', () => {
  const pos = src.indexOf('(def')
  expect(src.slice(...Object.values(topLevelFormAt(src, pos)))).toBe('(def bass\n  (notes c2 eb2))')
})

test('finds the form when the cursor is just past its closing paren', () => {
  const pos = src.indexOf('(def') + '(def bass\n  (notes c2 eb2))'.length
  expect(topLevelFormAt(src, pos)).not.toBe(null)
})

test('returns null in the blank space between forms', () => {
  const pos = src.indexOf('(ns jam)') + '(ns jam)'.length + 1
  expect(topLevelFormAt(src, pos)).toBe(null)
})

test('a string containing a paren does not confuse the scan', () => {
  const s = `(def a ")")\n(def b 1)\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('(def b'))
  expect(s.slice(from, to)).toBe('(def b 1)')
})

test('a character literal paren does not confuse the scan', () => {
  const s = `(def a \\()\n(def b 1)\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('(def b'))
  expect(s.slice(from, to)).toBe('(def b 1)')
})

test('a comment containing a paren does not confuse the scan', () => {
  const s = `(def a 1) ; )))\n(def b 1)\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('(def b'))
  expect(s.slice(from, to)).toBe('(def b 1)')
})

test('reader prefix #_ is included in the returned range', () => {
  const s = `#_(def old-bass 99)\n(def b 1)\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('old-bass'))
  expect(s.slice(from, to)).toBe('#_(def old-bass 99)')
})

test('reader prefix # on set literal is included in the returned range', () => {
  const s = `#{1 2 3}\n(def b 1)\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('1'))
  expect(s.slice(from, to)).toBe('#{1 2 3}')
})

test('a stray leading paren does not prevent a later flat form from being found', () => {
  const s = `)\n(def b 1)\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('(def'))
  expect(s.slice(from, to)).toBe('(def b 1)')
})

test('a stray closing paren does not cause a nested subform to be returned as top-level', () => {
  const s = `)\n(def bass\n  (notes c2 eb2))\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('notes'))
  expect(s.slice(from, to)).toBe('(def bass\n  (notes c2 eb2))')
})

test('findNamespace ignores (ns ...) inside a comment', () => {
  const s = `; (ns fake)\n(ns real)\n`
  expect(findNamespace(s)).toBe('real')
})

test('findNamespace ignores (ns ...) inside a string', () => {
  const s = `(def doc "(ns fake)")\n(ns real)\n`
  expect(findNamespace(s)).toBe('real')
})

test('a symbol ending in quote does not swallow the prefix', () => {
  const s = `state'(next-fn 1)\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('next-fn'))
  expect(s.slice(from, to)).toBe('(next-fn 1)')
})

test('a symbol ending in underscore does not swallow the prefix', () => {
  const s = `x_(foo 1)\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('foo'))
  expect(s.slice(from, to)).toBe('(foo 1)')
})

test('a legitimately quoted form at line start keeps its quote', () => {
  const s = `(def a 1)\n'(1 2 3)\n`
  const { from, to } = topLevelFormAt(s, s.indexOf('1 2 3'))
  expect(s.slice(from, to)).toBe("'(1 2 3)")
})
