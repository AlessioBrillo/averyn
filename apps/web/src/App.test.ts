import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { expect, test } from 'vitest'
import { App } from './App'

test('renders the app title', () => {
  expect(renderToString(createElement(App))).toContain('Averyn')
})
