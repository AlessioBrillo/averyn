// The language of the pages (issue #69): English and Italian.
//
// The English text IS the key: `t('Your data')` is the text itself when the language is English, and its Italian
// translation from `it.ts` when it is Italian. A text without a translation is shown in English, so a string added
// without a translation is never blank or broken; `i18n.test.ts` fails the build for it all the same.
//
// Placeholders are `{name}` and are filled from the second argument. There is no plural machinery on purpose: the
// texts are written so that they do not need one ("{n} of your activities…").

import { it } from './it'

export type Lang = 'en' | 'it'

export const LANGUAGES: { code: Lang; name: string }[] = [
  { code: 'en', name: 'English' },
  { code: 'it', name: 'Italiano' },
]

const dictionaries: Record<Lang, Record<string, string>> = { en: {}, it }

const STORAGE_KEY = 'averyn.language'

/** The remembered choice, else the language of the browser; English outside a browser (tests, rendering). */
function detect(): Lang {
  if (typeof window === 'undefined') return 'en'
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    if (stored === 'en' || stored === 'it') return stored
  } catch {
    // Storage can be blocked: the browser's language decides.
  }
  return window.navigator.language.toLowerCase().startsWith('it') ? 'it' : 'en'
}

let current: Lang = detect()

export const getLang = (): Lang => current

/** Changes the language and remembers it on this device. The caller re-renders (the app remounts its content). */
export function setLang(lang: Lang): void {
  current = lang
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(STORAGE_KEY, lang)
  } catch {
    // Not remembering is harmless.
  }
}

/** The text in the current language; `params` fills the `{name}` placeholders. */
export function t(text: string, params?: Record<string, string | number>): string {
  const template = dictionaries[current][text] ?? text
  if (!params) return template
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => (name in params ? String(params[name]) : whole))
}
