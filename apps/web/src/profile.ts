// The rules of the maximum heart rate as the form checks them. The server checks them again (TDD-0012).

export const MIN_MAX_HR_BPM = 100
export const MAX_MAX_HR_BPM = 230

/** What the user typed: nothing means "not set"; otherwise a whole number in range, or an error. */
export function parseMaxHr(text: string): { ok: true; value: number | null } | { ok: false } {
  const trimmed = text.trim()
  if (trimmed === '') return { ok: true, value: null }
  if (!/^\d+$/.test(trimmed)) return { ok: false }
  const value = Number(trimmed)
  return value >= MIN_MAX_HR_BPM && value <= MAX_MAX_HR_BPM ? { ok: true, value } : { ok: false }
}
