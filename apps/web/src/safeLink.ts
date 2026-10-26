/** A link only if it is an http(s) URL: the server checks it, the page does not trust it (the link is opened). */
export function safeLink(value: string | null | undefined): string | null {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.toString() : null
  } catch {
    return null
  }
}
