#!/usr/bin/env bash
# google_oauth.sh — one-time consent on a computer, producing a refresh token for the phone.
#
# The phone never runs a browser flow: it only ever exchanges this refresh token for
# short-lived access tokens. Run this once per Google account.
#
# Prerequisites, in the Google Cloud console:
#   1. Create a project, enable the Gmail, Calendar and Drive APIs.
#   2. Create an OAuth client of type "Desktop app".
#   3. Add yourself as a test user on the consent screen.
set -uo pipefail

SCOPES="https://www.googleapis.com/auth/gmail.modify https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/drive.readonly"
REDIRECT="urn:ietf:wg:oauth:2.0:oob"

command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }

read -r -p "Client id: " CLIENT_ID
read -r -s -p "Client secret: " CLIENT_SECRET; echo
[ -n "$CLIENT_ID" ] && [ -n "$CLIENT_SECRET" ] || { echo "both values are required" >&2; exit 1; }

ENCODED_SCOPES="$(printf '%s' "$SCOPES" | sed 's/ /%20/g; s|:|%3A|g; s|/|%2F|g')"
AUTH_URL="https://accounts.google.com/o/oauth2/v2/auth?client_id=${CLIENT_ID}&redirect_uri=${REDIRECT}&response_type=code&access_type=offline&prompt=consent&scope=${ENCODED_SCOPES}"

cat <<EOF

Open this URL, approve the scopes, and copy the code Google shows you:

$AUTH_URL

EOF

read -r -p "Authorization code: " CODE
[ -n "$CODE" ] || { echo "no code entered" >&2; exit 1; }

RESPONSE="$(curl -s -X POST https://oauth2.googleapis.com/token \
  -d "client_id=${CLIENT_ID}" \
  -d "client_secret=${CLIENT_SECRET}" \
  -d "code=${CODE}" \
  -d "grant_type=authorization_code" \
  -d "redirect_uri=${REDIRECT}")"

TOKEN="$(printf '%s' "$RESPONSE" | grep -o '"refresh_token"[^,]*' | cut -d'"' -f4)"

if [ -z "$TOKEN" ]; then
  echo "No refresh token came back. Google's response was:" >&2
  printf '%s\n' "$RESPONSE" >&2
  echo >&2
  echo "If it mentions invalid_grant the code was already used or expired — rerun this." >&2
  exit 1
fi

cat <<EOF

Refresh token:

  $TOKEN

Enter this, the client id and the client secret in the app under
Settings -> Connectors. They are stored in Keystore-backed encrypted preferences
and never leave the phone.

Treat the refresh token like a password: it grants access to the mailbox until revoked
at https://myaccount.google.com/permissions
EOF
