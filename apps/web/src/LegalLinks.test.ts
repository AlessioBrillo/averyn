import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { expect, test } from 'vitest'
import type { ClientConfig } from './api'
import { LegalLinks } from './LegalLinks'
import { safeLink } from './safeLink'

const base: ClientConfig = { issuer: 'https://idp', clientId: 'a', webClientId: 'w', mapStyleUrl: 'https://s' }

test('the footer links to the documents of the operator, when it has published them', () => {
  const html = renderToString(
    createElement(LegalLinks, {
      config: { ...base, privacyPolicyUrl: 'https://example.org/privacy', termsUrl: 'https://example.org/terms' },
    }),
  )

  expect(html).toContain('href="https://example.org/privacy"')
  expect(html).toContain('Privacy policy')
  expect(html).toContain('href="https://example.org/terms"')
  expect(html).toContain('rel="noopener noreferrer"')
})

test('with one document only one link is shown, with none there is no footer', () => {
  const one = renderToString(createElement(LegalLinks, { config: { ...base, termsUrl: 'https://example.org/terms' } }))
  expect(one).toContain('Terms of service')
  expect(one).not.toContain('Privacy policy')
  expect(renderToString(createElement(LegalLinks, { config: base }))).toBe('')
})

test('only http and https are ever linked', () => {
  expect(safeLink('https://example.org/p')).toBe('https://example.org/p')
  expect(safeLink('http://example.org/p')).toBe('http://example.org/p')
  expect(safeLink('javascript:alert(1)')).toBeNull()
  expect(safeLink('/relative')).toBeNull()
  expect(safeLink('')).toBeNull()
  expect(safeLink(null)).toBeNull()
  const html = renderToString(
    createElement(LegalLinks, { config: { ...base, privacyPolicyUrl: 'javascript:alert(1)' } }),
  )
  expect(html).toBe('')
})
