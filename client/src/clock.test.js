import { beforeEach, expect, test } from 'vitest'
import { makeClock } from './clock.js'
import { OUTPUT_LATENCY_MS } from './config.js'

let clock
beforeEach(() => { clock = makeClock() })

test('a clock with no samples is not ready and refuses to convert', () => {
  expect(clock.ready()).toBe(false)
  expect(clock.rtt()).toBe(null)
  expect(() => clock.toAudioTime(1n)).toThrow()
})

test('one sample maps its own server instant to the midpoint of the round trip', () => {
  clock.addSample({ sentAt: 10.0, receivedAt: 10.020, serverNanos: 5_000_000_000n })
  expect(clock.ready()).toBe(true)
  // The midpoint is 10.010; add the output latency.
  expect(clock.toAudioTime(5_000_000_000n)).toBeCloseTo(10.010 + OUTPUT_LATENCY_MS / 1000, 9)
})

test('later server instants map further into the future, at one second per second', () => {
  clock.addSample({ sentAt: 10.0, receivedAt: 10.020, serverNanos: 5_000_000_000n })
  const a = clock.toAudioTime(5_000_000_000n)
  const b = clock.toAudioTime(5_500_000_000n)
  expect(b - a).toBeCloseTo(0.5, 9)
})

test('the lowest-RTT sample wins, because it is the least distorted by queueing', () => {
  clock.addSample({ sentAt: 10.0, receivedAt: 10.200, serverNanos: 5_000_000_000n }) // 200ms, bad
  clock.addSample({ sentAt: 11.0, receivedAt: 11.002, serverNanos: 6_000_000_000n }) // 2ms, good
  expect(clock.rtt()).toBeCloseTo(0.002, 9)
  // Anchored on the good sample: server 6e9 maps to client 11.001 + latency.
  expect(clock.toAudioTime(6_000_000_000n)).toBeCloseTo(11.001 + OUTPUT_LATENCY_MS / 1000, 9)
})

test('only the last PING_WINDOW samples count, so a stale good sample is forgotten', () => {
  clock.addSample({ sentAt: 0, receivedAt: 0.001, serverNanos: 1_000_000_000n }) // best, but old
  for (let i = 1; i <= 8; i++) {
    clock.addSample({ sentAt: i, receivedAt: i + 0.050, serverNanos: BigInt(i) * 1_000_000_000n })
  }
  expect(clock.rtt()).toBeCloseTo(0.050, 9)
})

test('precision survives instants beyond 2^53', () => {
  // 9007199254740993 is the first integer a double cannot represent.
  const big = 9_007_199_254_740_993n
  clock.addSample({ sentAt: 10.0, receivedAt: 10.0, serverNanos: big })
  // Millisecond-scale delta (999_999n) catches rounding errors at this magnitude.
  const d_ms = clock.toAudioTime(big + 999_999n) - clock.toAudioTime(big)
  expect(d_ms).toBeCloseTo(0.000999999, 9)
})

test('nanosecond resolution survives instants beyond 2^53', () => {
  // 9007199254740993 is the first integer a double cannot represent.
  const big = 9_007_199_254_740_993n
  clock.addSample({ sentAt: 10.0, receivedAt: 10.0, serverNanos: big })
  // Nanosecond-scale delta (1n) catches the precision bug at the finest scale.
  const d_ns = clock.toAudioTime(big + 1n) - clock.toAudioTime(big)
  expect(d_ns).toBeCloseTo(0.000000001, 9)
})

test('reset drops every sample, so the clock is unsynced again', () => {
  clock.addSample({ sentAt: 10.0, receivedAt: 10.020, serverNanos: 5_000_000_000n })
  expect(clock.ready()).toBe(true)
  clock.reset()
  expect(clock.ready()).toBe(false)
  expect(clock.rtt()).toBe(null)
  expect(() => clock.toAudioTime(5_000_000_000n)).toThrow()
})

test('latency is adjustable and shifts every conversion', () => {
  clock.addSample({ sentAt: 10.0, receivedAt: 10.0, serverNanos: 1_000_000_000n })
  const before = clock.toAudioTime(1_000_000_000n)
  clock.setLatency(200)
  expect(clock.latency()).toBe(200)
  expect(clock.toAudioTime(1_000_000_000n) - before)
    .toBeCloseTo((200 - OUTPUT_LATENCY_MS) / 1000, 9)
})
