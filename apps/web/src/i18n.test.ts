import { afterEach, describe, expect, it as test } from 'vitest'
import { getLang, setLang, t } from './i18n'
import { it } from './it'

// The source of every page, as text: the texts of `t('…')` are read from it.
const sources = import.meta.glob(['./*.ts', './*.tsx', '!./*.test.ts', '!./*.test.tsx', '!./i18n.ts', '!./it.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function usedTexts(): Set<string> {
  const used = new Set<string>()
  const literal = /\bt\(\s*(?:'((?:[^'\\]|\\.)*)'|`([^`$]*)`)/g
  for (const source of Object.values(sources)) {
    for (const m of source.matchAll(literal)) used.add((m[1] ?? m[2]).replace(/\\'/g, "'"))
  }
  return used
}

const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort()

describe('i18n', () => {
  afterEach(() => setLang('en'))

  test('every text of the pages has an Italian translation', () => {
    expect([...usedTexts()].filter((text) => !(text in it))).toEqual([])
  })

  test('no translation is dead', () => {
    const used = usedTexts()
    expect(Object.keys(it).filter((text) => !used.has(text))).toEqual([])
  })

  test('a translation keeps the placeholders of the text', () => {
    for (const [text, translation] of Object.entries(it)) {
      expect(placeholders(translation), text).toEqual(placeholders(text))
    }
  })

  test('English is the text itself, Italian is the translation, a missing one falls back to English', () => {
    expect(t('Your data')).toBe('Your data')
    setLang('it')
    expect(getLang()).toBe('it')
    expect(t('Your data')).toBe('I tuoi dati')
    expect(t('a text nobody translated')).toBe('a text nobody translated')
  })

  test('placeholders are filled, and an unknown one is left as it is', () => {
    expect(t('Zone {n}', { n: 3 })).toBe('Zone 3')
    setLang('it')
    expect(t('Zone {n}', { n: 3 })).toBe('Zona 3')
    expect(t('Hello {who}', {})).toBe('Hello {who}')
  })
})
