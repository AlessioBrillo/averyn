#!/bin/sh
# Creates the Averyn project and its public (PKCE) OIDC app in Zitadel, then writes the generated ids to
# /oidc/oidc.env. Idempotent: safe on every `docker compose up`. Runs inside the Compose network.
set -eu

# Zitadel picks its instance from the Host header, so call the service by name and send the issuer's host.
# (Not by IDP_HOST: curl resolves *.localhost to loopback itself and would bypass the Compose network alias.)
issuer="http://${IDP_HOST}:${IDP_PORT}"
base="http://idp:${IDP_PORT}"
curl() { command curl -H "Host: ${IDP_HOST}:${IDP_PORT}" "$@"; }
api() { curl -fsS -H "Authorization: Bearer ${pat}" -H 'Content-Type: application/json' "$@"; }
first() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n 1; }

echo "waiting for the identity provider at ${issuer}"
until curl -fsS "${base}/debug/ready" >/dev/null 2>&1 && [ -s /bootstrap/admin.pat ]; do sleep 2; done
pat=$(cat /bootstrap/admin.pat)

project_id=$(api -X POST "${base}/management/v1/projects/_search" \
  -d '{"queries":[{"nameQuery":{"name":"averyn","method":"TEXT_QUERY_METHOD_EQUALS"}}]}' | first id)
if [ -z "${project_id}" ]; then
  project_id=$(api -X POST "${base}/management/v1/projects" -d '{"name":"averyn"}' | first id)
fi

client_id=$(api -X POST "${base}/management/v1/projects/${project_id}/apps/_search" \
  -d '{"queries":[{"nameQuery":{"name":"averyn-app","method":"TEXT_QUERY_METHOD_EQUALS"}}]}' | first clientId)
if [ -z "${client_id}" ]; then
  # Native public client: authorization code + PKCE, no secret. The redirect scheme must match the apps' AppAuth config.
  client_id=$(api -X POST "${base}/management/v1/projects/${project_id}/apps/oidc" -d '{
    "name": "averyn-app",
    "redirectUris": ["dev.averyn.app:/oauth2redirect"],
    "postLogoutRedirectUris": ["dev.averyn.app:/oauth2redirect"],
    "responseTypes": ["OIDC_RESPONSE_TYPE_CODE"],
    "grantTypes": ["OIDC_GRANT_TYPE_AUTHORIZATION_CODE", "OIDC_GRANT_TYPE_REFRESH_TOKEN"],
    "appType": "OIDC_APP_TYPE_NATIVE",
    "authMethodType": "OIDC_AUTH_METHOD_TYPE_NONE",
    "accessTokenType": "OIDC_TOKEN_TYPE_JWT",
    "devMode": true
  }' | first clientId)
fi

[ -n "${project_id}" ] && [ -n "${client_id}" ] || { echo "could not determine project/client id" >&2; exit 1; }

# The project id is in every access token's `aud`, so it is the audience the backend checks.
printf 'AVERYN_OIDC_ISSUER=%s
AVERYN_OIDC_AUDIENCE=%s
AVERYN_OIDC_CLIENT_ID=%s
'   "${issuer}" "${project_id}" "${client_id}" > /oidc/oidc.env.tmp
mv /oidc/oidc.env.tmp /oidc/oidc.env
echo "identity provider ready: project ${project_id}, client ${client_id}"
