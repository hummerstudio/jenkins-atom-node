#!/usr/bin/env bash
# A fake node installation proves the contract: NODE_HOME and PATH come back as outputs, the command runs with them.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"
mkdir -p "$work/node/bin"
cat >"$work/node/bin/node" <<'SH'
#!/bin/sh
case "$1" in
  --version) echo "v20.11.1" ;;
  *) echo "ran with node from $0" ;;
esac
SH
cat >"$work/node/bin/npm" <<'SH'
#!/bin/sh
echo "10.2.4"
SH
chmod +x "$work/node/bin/node" "$work/node/bin/npm"

# 1. nodeHome + a command: NODE_HOME, NODE_VERSION, npmVersion, PATH
dir="$work/run"; mkdir -p "$dir"
printf '{"nodeHome": "%s", "command": "echo building with $NODE_HOME", "prependPath": "true"}' "$work/node" >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" NODE_HOME)" = "$work/node" ] || fail "NODE_HOME: $(cat "$dir/output.json")"
[ "$(output "$dir" NODE_VERSION)" = "v20.11.1" ] || fail "version: $(cat "$dir/output.json")"
[ "$(output "$dir" npmVersion)" = "10.2.4" ] || fail "npm version: $(cat "$dir/output.json")"
case "$(output "$dir" PATH)" in
  "$work/node/bin:"*) : ;;
  *) fail "PATH must start with the node directory: $(output "$dir" PATH)" ;;
esac
grep -q "building with $work/node" "$dir/log.txt" || fail "the command did not see NODE_HOME: $(cat "$dir/log.txt")"

# 2. a bin directory is accepted as well as a home
dir="$work/bin"; mkdir -p "$dir"
printf '{"nodeHome": "%s", "prependPath": "false"}' "$work/node/bin" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "bin dir run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" NODE_VERSION)" = "v20.11.1" ] || fail "bin dir version"
[ -n "$(output "$dir" PATH)" ] && fail "prependPath=false must not emit PATH"

# 3. a wrong directory is a clear error
dir="$work/bad"; mkdir -p "$dir"
printf '{"nodeHome": "%s/node"}' "$work/nonexistent" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a missing node must fail"
grep -q "no node binary" "$dir/output.json" || fail "message not readable: $(cat "$dir/output.json")"

# a failing command must fail the atom, not pass silently
dir="$work/failing"; mkdir -p "$dir"
printf '{"nodeHome": "%s", "command": "echo boom; exit 3"}' "$work/node" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a command exiting 3 must fail the atom"
grep -q "exited 3" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

# unless the caller says otherwise
dir="$work/tolerant"; mkdir -p "$dir"
printf '{"nodeHome": "%s", "command": "exit 3", "failOnCommandError": "false"}' "$work/node" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "failOnCommandError=false must not fail"
grep -q '"exitCode":{"type":"string","value":"3"}' "$dir/output.json" || fail "exit code output"

echo "smoke ok: NODE_VERSION/npm/PATH outputs, bin dir, missing node, failing command"
