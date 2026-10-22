#!/usr/bin/env bash
# Create the GitHub repo (if missing) and push the current branch, using GITHUB_TOKEN from ~/.env.
# Usage: tools/publish_github.sh [repo-name] [--public]
# Private by default. The token is written only to 0600 temporary files (a curl config and a git credential
# store), never to a command line, .git/config or the remote URL, and both files are removed when this ends.
set -euo pipefail

NAME="living-world"
PRIVATE=true
for arg in "$@"; do
  case "$arg" in
    --public) PRIVATE=false ;;
    *) NAME="$arg" ;;
  esac
done

ENV_FILE="${LW_ENV_FILE:-}"
if [ -z "$ENV_FILE" ]; then
  # PRoot's $HOME (/root) differs from the Termux home that holds the token.
  for f in "$HOME/.env" /data/data/com.termux/files/home/.env /root/termux-home/.env; do
    [ -r "$f" ] && { ENV_FILE="$f"; break; }
  done
fi
[ -r "${ENV_FILE:-}" ] || { echo "no readable .env with GITHUB_TOKEN (set LW_ENV_FILE)" >&2; exit 1; }
GITHUB_TOKEN="$(sed -n 's/^GITHUB_TOKEN=//p' "$ENV_FILE" | head -1 | tr -d '"'"'"'\r')"
[ -n "$GITHUB_TOKEN" ] || { echo "GITHUB_TOKEN not set in $ENV_FILE" >&2; exit 1; }

# The token must not show up in `ps`, so it goes into files that only this user can read.
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
chmod 700 "$TMP_DIR"
HEADER_FILE="$TMP_DIR/curl-header"
CRED_FILE="$TMP_DIR/git-credentials"
printf 'header = "Authorization: Bearer %s"\n' "$GITHUB_TOKEN" > "$HEADER_FILE"
printf 'https://x-access-token:%s@github.com\n' "$GITHUB_TOKEN" > "$CRED_FILE"
chmod 600 "$HEADER_FILE" "$CRED_FILE"

api() { curl -sS --config "$HEADER_FILE" -H "Accept: application/vnd.github+json" "$@"; }

OWNER="$(api https://api.github.com/user | python3 -c 'import sys,json; print(json.load(sys.stdin).get("login",""))')"
[ -n "$OWNER" ] || { echo "token rejected by GitHub" >&2; exit 1; }

STATUS="$(api -o /dev/null -w '%{http_code}' "https://api.github.com/repos/$OWNER/$NAME")"
if [ "$STATUS" = "404" ]; then
  BODY="$(NAME="$NAME" PRIVATE="$PRIVATE" python3 -c 'import json, os; print(json.dumps({"name": os.environ["NAME"], "private": os.environ["PRIVATE"] == "true", "description": "Deterministic event-sourced KMP simulation kernel for a tactical RPG"}))')"
  api -X POST https://api.github.com/user/repos -d "$BODY" >/dev/null
  echo "created $OWNER/$NAME (private=$PRIVATE)"
elif [ "$STATUS" = "200" ]; then
  echo "$OWNER/$NAME already exists"
else
  echo "unexpected status $STATUS checking $OWNER/$NAME" >&2; exit 1
fi

URL="https://github.com/$OWNER/$NAME.git"
git remote get-url origin >/dev/null 2>&1 && git remote set-url origin "$URL" || git remote add origin "$URL"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git -c credential.helper= -c credential.helper="store --file=$CRED_FILE" push -u origin "$BRANCH"
echo "pushed $BRANCH to $URL"
