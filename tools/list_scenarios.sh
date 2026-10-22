#!/usr/bin/env bash
# Validate tests/golden/scenarios.json and print it for CI: the run length on the first line, then one scenario per line.
# Any problem (unreadable file, missing key, empty list, a non-string, unsafe name, days not a whole number above zero)
# exits non-zero and prints nothing, so a step that captures the output with set -e cannot go on with zero goldens.
set -euo pipefail
file=${1:-tests/golden/scenarios.json}

# Same rules as tools/scenarios_config.py: \A..\z anchors so an embedded newline cannot pass, names distinct, days a whole
# number written as plain digits (jq 1.7 would print 10.0 for a float, which the Python tools reject too).
jq -e '(.scenarios | type == "array") and (.scenarios | length > 0)
       and all(.scenarios[]; type == "string" and test("\\A[a-z][a-z0-9_]*\\z"))
       and ((.scenarios | length) == (.scenarios | unique | length))' "$file" > /dev/null
days=$(jq -er '.days | numbers | select(. > 0 and . == floor) | tostring | select(test("\\A[0-9]+\\z"))' "$file")
names=$(jq -r '.scenarios[]' "$file")

# Names reach the shell and the Gradle arguments: plain words only.
while IFS= read -r name; do
  [[ "$name" =~ ^[a-z][a-z0-9_]*$ ]] || { echo "bad scenario name: $name" >&2; exit 1; }
done <<< "$names"

printf '%s\n%s\n' "$days" "$names"
