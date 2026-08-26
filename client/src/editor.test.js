import { expect, test } from 'vitest'
import { topLevelFormAt } from './editor.js'

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
