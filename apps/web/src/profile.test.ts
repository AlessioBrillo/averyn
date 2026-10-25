import { expect, test } from 'vitest'
import { parseMaxHr } from './profile'

test('nothing typed means no maximum heart rate', () => {
  expect(parseMaxHr('')).toEqual({ ok: true, value: null })
  expect(parseMaxHr('  ')).toEqual({ ok: true, value: null })
})

test('a whole number from 100 to 230 is accepted, the bounds included', () => {
  expect(parseMaxHr('187')).toEqual({ ok: true, value: 187 })
  expect(parseMaxHr(' 100 ')).toEqual({ ok: true, value: 100 })
  expect(parseMaxHr('230')).toEqual({ ok: true, value: 230 })
})

test('anything else is refused', () => {
  for (const bad of ['99', '231', '-5', '18.5', '1e2', 'fast', '0x90']) {
    expect(parseMaxHr(bad), bad).toEqual({ ok: false })
  }
})
