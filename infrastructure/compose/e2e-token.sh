#!/bin/sh
# CI only (TDD-0006 Q3/Q4): prints an access token of a throwaway machine user of the Averyn project, issued by the
# real identity provider, so a job can call the signed-in API without a browser. Run in the idp-init image, which
# already has curl and both volumes:
#   docker compose run --rm --no-deps -v ./e2e-token.sh:/e2e-token.sh:ro --entrypoint /bin/sh idp-init /e2e-token.sh
# The token's audience is the project id, like the tokens of the apps; nothing else is printed on stdout.
set -eu

issuer="${IDP_ISSUER}"
base="http://idp:${IDP_PORT}"
curl() { command curl -H "Host: ${issuer#*://}" "$@"; }
first() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n 1; }
pat=$(cat /bootstrap/admin.pat)
# shellcheck disable=SC1091
. /oidc/oidc.env

admin() { curl -fsS -H "Authorization: Bearer ${pat}" -H 'Content-Type: application/json' "$@"; }

user=$(admin -X POST "${base}/management/v1/users/machine" \
  -d '{"userName":"e2e","name":"e2e","accessTokenType":"ACCESS_TOKEN_TYPE_JWT"}')
user_id=$(printf '%s' "${user}" | first userId)
secret=$(admin -X PUT "${base}/management/v1/users/${user_id}/secret" -d '{}')
client_id=$(printf '%s' "${secret}" | first clientId)
client_secret=$(printf '%s' "${secret}" | first clientSecret)

token=$(curl -fsS -u "${client_id}:${client_secret}" "${base}/oauth/v2/token" \
  -d grant_type=client_credentials \
  --data-urlencode "scope=openid urn:zitadel:iam:org:project:id:${AVERYN_OIDC_AUDIENCE}:aud")
printf '%s' "${token}" | first access_token
