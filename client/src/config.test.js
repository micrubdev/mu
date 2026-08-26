import { expect, test } from 'vitest'
import * as config from './config.js'

test('config exports the timing constants the rest of the client depends on', () => {
  expect(config.OUTPUT_LATENCY_MS).toBe(80)
  expect(config.PING_INTERVAL_MS).toBe(2000)
  expect(config.PING_WINDOW).toBe(8)
  expect(config.RECONNECT_MIN_MS).toBeLessThan(config.RECONNECT_MAX_MS)
})
