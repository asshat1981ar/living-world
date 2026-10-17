#!/usr/bin/env bash
# Create the GitHub repo (if missing) and push the current branch, using GITHUB_TOKEN from ~/.env.
# Usage: tools/publish_github.sh [repo-name] [--public]
# Private by default. The token is only sent as an HTTP header; it is never written to .git/config or the remote URL.
set -euo pipefail

NAME="living-world"
PRIVATE=true
for arg in "$@"; do
  case "$arg" in
    --public) PRIVATE=false ;;
    *) NAME="$arg" ;;
  esac
done

ENV_FILE="${LW_ENV_FILE:-$HOME/.env}"
[ -r "$ENV_FILE" ] || { echo "missing $ENV_FILE" >&2; exit 1; }
GITHUB_TOKEN="$(sed -n 's/^GITHUB_TOKEN=//p' "$ENV_FILE" | head -1 | tr -d '"'"'"'\r')"
[ -n "$GITHUB_TOKEN" ] || { echo "GITHUB_TOKEN not set in $ENV_FILE" >&2; exit 1; }

api() { curl -sS -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

OWNER="$(api https://api.github.com/user | python3 -c 'import sys,json; print(json.load(sys.stdin).get("login",""))')"
[ -n "$OWNER" ] || { echo "token rejected by GitHub" >&2; exit 1; }

STATUS="$(api -o /dev/null -w '%{http_code}' "https://api.github.com/repos/$OWNER/$NAME")"
if [ "$STATUS" = "404" ]; then
  api -X POST https://api.github.com/user/repos \
    -d "{\"name\":\"$NAME\",\"private\":$PRIVATE,\"description\":\"Deterministic event-sourced KMP simulation kernel for a tactical RPG\"}" >/dev/null
  echo "created $OWNER/$NAME (private=$PRIVATE)"
elif [ "$STATUS" = "200" ]; then
  echo "$OWNER/$NAME already exists"
else
  echo "unexpected status $STATUS checking $OWNER/$NAME" >&2; exit 1
fi

URL="https://github.com/$OWNER/$NAME.git"
git remote get-url origin >/dev/null 2>&1 && git remote set-url origin "$URL" || git remote add origin "$URL"

AUTH="$(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 -w0)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git -c "http.extraheader=Authorization: Basic $AUTH" push -u origin "$BRANCH"
echo "pushed $BRANCH to $URL"
