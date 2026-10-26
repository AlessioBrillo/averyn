import type { ClientConfig } from './api'
import { safeLink } from './safeLink'

/**
 * The documents of the operator of this instance: the privacy notice and the terms (TDD-0012 launch checklist).
 * Averyn does not write them, the operator does; with none configured the footer is not there at all.
 */
export function LegalLinks({ config }: { config: ClientConfig }) {
  const privacy = safeLink(config.privacyPolicyUrl)
  const terms = safeLink(config.termsUrl)
  if (!privacy && !terms) return null
  return (
    <footer>
      <p className="small">
        {privacy && (
          <a href={privacy} rel="noopener noreferrer" target="_blank">
            Privacy policy
          </a>
        )}
        {privacy && terms && ' · '}
        {terms && (
          <a href={terms} rel="noopener noreferrer" target="_blank">
            Terms of service
          </a>
        )}
      </p>
    </footer>
  )
}
