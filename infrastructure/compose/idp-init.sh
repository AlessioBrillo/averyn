#!/bin/sh
# Creates the Averyn project and its public (PKCE) OIDC app in Zitadel, then writes the generated ids to
# /oidc/oidc.env. Idempotent: safe on every `docker compose up`. Runs inside the Compose network.
set -eu

# Zitadel picks its instance from the Host header, so call the service by name and send the issuer's host.
# (Not by IDP_HOST: curl resolves *.localhost to loopback itself and would bypass the Compose network alias.)
# IDP_ISSUER is http://IDP_HOST:IDP_PORT, or https://IDP_HOST behind the TLS edge (ADR-0018).
issuer="${IDP_ISSUER}"
base="http://idp:${IDP_PORT}"
curl() { command curl -H "Host: ${issuer#*://}" "$@"; }
# Development mode lets the apps use plain-HTTP redirect URIs; off as soon as the issuer is https.
case "${issuer}" in https://*) dev_mode=false ;; *) dev_mode=true ;; esac

# One management API call; prints the response body. Right after start the IdP can answer "ready" before its
# projections have caught up with the bootstrap user (so the first calls are refused), hence the retries.
api() {
  attempt=0
  while :; do
    out=$(curl -sS -w '\n%{http_code}' -H "Authorization: Bearer ${pat}" -H 'Content-Type: application/json' "$@" 2>&1) || true
    code=$(printf '%s' "${out}" | tail -n 1)
    case "${code}" in
      2??) printf '%s' "${out}" | sed '$d'; return 0 ;;
    esac
    attempt=$((attempt + 1))
    if [ "${attempt}" -ge 30 ]; then
      echo "identity provider API call failed (HTTP ${code}): $(printf '%s' "${out}" | sed '$d')" >&2
      return 1
    fi
    sleep 2
  done
}
first() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n 1; }

echo "waiting for the identity provider at ${issuer}"
until curl -fsS "${base}/debug/ready" >/dev/null 2>&1 && [ -s /bootstrap/admin.pat ]; do sleep 2; done
pat=$(cat /bootstrap/admin.pat)

# Each call is assigned to a variable first: with `set -e` a failed call stops the script (a pipeline would not).
found=$(api -X POST "${base}/management/v1/projects/_search" \
  -d '{"queries":[{"nameQuery":{"name":"averyn","method":"TEXT_QUERY_METHOD_EQUALS"}}]}')
project_id=$(printf '%s' "${found}" | first id)
if [ -z "${project_id}" ]; then
  created=$(api -X POST "${base}/management/v1/projects" -d '{"name":"averyn"}')
  project_id=$(printf '%s' "${created}" | first id)
fi

found=$(api -X POST "${base}/management/v1/projects/${project_id}/apps/_search" \
  -d '{"queries":[{"nameQuery":{"name":"averyn-app","method":"TEXT_QUERY_METHOD_EQUALS"}}]}')
client_id=$(printf '%s' "${found}" | first clientId)
if [ -z "${client_id}" ]; then
  # Native public client: authorization code + PKCE, no secret. The redirect scheme must match the apps' AppAuth config.
  created=$(api -X POST "${base}/management/v1/projects/${project_id}/apps/oidc" -d '{
    "name": "averyn-app",
    "redirectUris": ["dev.averyn.app:/oauth2redirect"],
    "postLogoutRedirectUris": ["dev.averyn.app:/oauth2redirect"],
    "responseTypes": ["OIDC_RESPONSE_TYPE_CODE"],
    "grantTypes": ["OIDC_GRANT_TYPE_AUTHORIZATION_CODE", "OIDC_GRANT_TYPE_REFRESH_TOKEN"],
    "appType": "OIDC_APP_TYPE_NATIVE",
    "authMethodType": "OIDC_AUTH_METHOD_TYPE_NONE",
    "accessTokenType": "OIDC_TOKEN_TYPE_JWT",
    "devMode": '"${dev_mode}"'
  }')
  client_id=$(printf '%s' "${created}" | first clientId)
fi

# The web app (TDD-0003): a public browser client in the same project, so its tokens carry the same audience.
# WEB_ORIGIN is the public URL of the web app; the redirect must match what the SPA sends, character for character.
found=$(api -X POST "${base}/management/v1/projects/${project_id}/apps/_search" \
  -d '{"queries":[{"nameQuery":{"name":"averyn-web","method":"TEXT_QUERY_METHOD_EQUALS"}}]}')
web_client_id=$(printf '%s' "${found}" | first clientId)
if [ -z "${web_client_id}" ]; then
  created=$(api -X POST "${base}/management/v1/projects/${project_id}/apps/oidc" -d '{
    "name": "averyn-web",
    "redirectUris": ["'"${WEB_ORIGIN}"'/callback"],
    "postLogoutRedirectUris": ["'"${WEB_ORIGIN}"'/"],
    "responseTypes": ["OIDC_RESPONSE_TYPE_CODE"],
    "grantTypes": ["OIDC_GRANT_TYPE_AUTHORIZATION_CODE", "OIDC_GRANT_TYPE_REFRESH_TOKEN"],
    "appType": "OIDC_APP_TYPE_USER_AGENT",
    "authMethodType": "OIDC_AUTH_METHOD_TYPE_NONE",
    "accessTokenType": "OIDC_TOKEN_TYPE_JWT",
    "devMode": '"${dev_mode}"'
  }')
  web_client_id=$(printf '%s' "${created}" | first clientId)
fi

if [ -z "${project_id}" ] || [ -z "${client_id}" ] || [ -z "${web_client_id}" ]; then
  echo "could not determine project/client id" >&2
  exit 1
fi

# The project id is in every access token's `aud`, so it is the audience the backend checks.
# Single-quoted: the backend entrypoint sources this file with a shell.
printf "AVERYN_OIDC_ISSUER='%s'\nAVERYN_OIDC_AUDIENCE='%s'\nAVERYN_OIDC_CLIENT_ID='%s'\nAVERYN_OIDC_WEB_CLIENT_ID='%s'\n" \
  "${issuer}" "${project_id}" "${client_id}" "${web_client_id}" > /oidc/oidc.env.tmp
mv /oidc/oidc.env.tmp /oidc/oidc.env
echo "identity provider ready: project ${project_id}, client ${client_id}, web client ${web_client_id}"
