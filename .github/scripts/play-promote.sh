#!/usr/bin/env bash
#
# Releases a version to Google Play production on the phone, Android TV and Android Automotive
# OS, by putting the release that the closed test tracks already hold on each production track.
# Nothing is uploaded: Play refuses a versionCode it has seen before, so production takes the
# closed release's versionCodes, name and release notes as they are.
#
# All three production tracks change in one edit, which is committed once, so either all of
# them get the release or none does. Running it again puts the same release there again, so
# after a failure GitHub's "Re-run failed jobs" repeats only this step, once the closed uploads
# have succeeded.
#
# Usage: play-promote.sh <release name> [--dry-run]
#   The release name is the tag, as release.yml names the closed releases, for example v2.44.0.
#   --dry-run reads and prepares everything, then deletes the edit instead of committing it.
#
# The service account key is read from PLAY_SERVICE_ACCOUNT_JSON. Neither the key nor the
# access token is ever printed. Needs curl, jq and openssl.

set -euo pipefail

PACKAGE="net.asksakis.massdroidv2"
API="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}"
SCOPE="https://www.googleapis.com/auth/androidpublisher"
# Each closed track and the production track its release goes to, by position.
SOURCES=(alpha tv:closed automotive:closed)
TARGETS=(production tv:production automotive:production)

die() { echo "play-promote: $*" >&2; exit 1; }

[ $# -ge 1 ] || die "usage: play-promote.sh <release name> [--dry-run]"
RELEASE_NAME="$1"
DRY_RUN=false
[ "${2:-}" = "--dry-run" ] && DRY_RUN=true
[ -n "${PLAY_SERVICE_ACCOUNT_JSON:-}" ] || die "PLAY_SERVICE_ACCOUNT_JSON is empty"

WORK_DIR="$(mktemp -d)"
EDIT_ID=""
COMMITTED=false

# Calls curl and prints the answer; on an HTTP error it prints Play's error body to stderr and
# fails. The bodies hold no secret: the token travels in a header file, never in the output.
# A transient 5xx is retried, as Play answered one 503 in the first trial run; the commit is
# called once only (call_once), because a retry after a commit that did land would fail on an
# edit that no longer exists and report the release as failed.
call() { call_once --retry 3 --retry-delay 5 "$@"; }

call_once() {
    local body
    if ! body="$(curl -sS --fail-with-body "$@")"; then
        echo "$body" >&2
        return 1
    fi
    printf '%s' "$body"
}

api() { call -H "@${WORK_DIR}/auth-header" "$@"; }
api_once() { call_once -H "@${WORK_DIR}/auth-header" "$@"; }

cleanup() {
    # An edit left open on an error is deleted, so it cannot block or confuse a later run.
    if [ -n "$EDIT_ID" ] && [ "$COMMITTED" = false ] && [ -f "${WORK_DIR}/auth-header" ]; then
        api -X DELETE "${API}/edits/${EDIT_ID}" > /dev/null 2>&1 || true
    fi
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }

# A service account signs its own token request (a JWT, RS256) and trades it for an access token.
umask 077
jq -r '.private_key' <<< "$PLAY_SERVICE_ACCOUNT_JSON" > "${WORK_DIR}/key.pem"
CLIENT_EMAIL="$(jq -r '.client_email' <<< "$PLAY_SERVICE_ACCOUNT_JSON")"
TOKEN_URI="$(jq -r '.token_uri // "https://oauth2.googleapis.com/token"' <<< "$PLAY_SERVICE_ACCOUNT_JSON")"
NOW="$(date +%s)"
HEADER="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
CLAIMS="$(jq -nc --arg iss "$CLIENT_EMAIL" --arg scope "$SCOPE" --arg aud "$TOKEN_URI" \
    --argjson iat "$NOW" --argjson exp "$((NOW + 3600))" \
    '{iss: $iss, scope: $scope, aud: $aud, iat: $iat, exp: $exp}' | b64url)"
SIGNATURE="$(printf '%s.%s' "$HEADER" "$CLAIMS" | openssl dgst -sha256 -sign "${WORK_DIR}/key.pem" -binary | b64url)"
rm -f "${WORK_DIR}/key.pem"
# The signed assertion is a credential as well, so it also reaches curl through a file.
printf '%s.%s.%s' "$HEADER" "$CLAIMS" "$SIGNATURE" > "${WORK_DIR}/assertion"
unset SIGNATURE
TOKEN_RESPONSE="$(call -X POST "$TOKEN_URI" \
    --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer" \
    --data-urlencode "assertion@${WORK_DIR}/assertion")" || die "the token request failed"
rm -f "${WORK_DIR}/assertion"
ACCESS_TOKEN="$(jq -r '.access_token // empty' <<< "$TOKEN_RESPONSE")"
unset TOKEN_RESPONSE
[ -n "$ACCESS_TOKEN" ] || die "no access token from ${TOKEN_URI}"
# The token goes to curl through a file, so it never appears on a command line.
printf 'Authorization: Bearer %s\n' "$ACCESS_TOKEN" > "${WORK_DIR}/auth-header"
unset ACCESS_TOKEN

# Called once: a retry after an edit that was created but answered with an error would open a
# second edit that nothing deletes.
EDIT_RESPONSE="$(api_once -X POST "${API}/edits")" || die "Play did not open an edit"
EDIT_ID="$(jq -r '.id // empty' <<< "$EDIT_RESPONSE")"
[ -n "$EDIT_ID" ] || die "Play did not open an edit"

for i in "${!SOURCES[@]}"; do
    source="${SOURCES[$i]}"
    target="${TARGETS[$i]}"
    track="$(api "${API}/edits/${EDIT_ID}/tracks/${source}")" || die "${source}: the track could not be read"
    release="$(jq -c --arg name "$RELEASE_NAME" '
        [.releases[]? | select(.name == $name and .status == "completed")] | first // empty
        | {name, versionCodes, releaseNotes, status: "completed"}
        | with_entries(select(.value != null))' <<< "$track")"
    [ -n "$release" ] || die "${source}: no completed release named ${RELEASE_NAME}"
    jq -nc --arg track "$target" --argjson release "$release" '{track: $track, releases: [$release]}' \
        > "${WORK_DIR}/track.json"
    api -X PUT -H 'Content-Type: application/json' --data-binary "@${WORK_DIR}/track.json" \
        "${API}/edits/${EDIT_ID}/tracks/${target}" > /dev/null || die "${target}: the release was refused"
    echo "${target}: ${RELEASE_NAME} $(jq -c '.versionCodes' <<< "$release") from ${source}," \
        "$(jq '.releaseNotes // [] | length' <<< "$release") release note language(s), status completed"
done

if [ "$DRY_RUN" = true ]; then
    api -X DELETE "${API}/edits/${EDIT_ID}" > /dev/null || die "the dry run's edit could not be deleted"
    EDIT_ID=""
    echo "Dry run: the edit was deleted, nothing changed on Google Play."
else
    api_once -X POST "${API}/edits/${EDIT_ID}:commit" > /dev/null || die "Play refused to commit the edit"
    COMMITTED=true
    echo "Committed: ${RELEASE_NAME} is on production for the phone, Android TV and Android Automotive OS."
fi
