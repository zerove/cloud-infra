#!/usr/bin/env bash
# Negative control for check_compose_dangling_deps.py
#
# RULE (project lesson, 5th lesson note 1): "a guard that was added" != "a guard
# that guards". The only proof is a negative control: break it, confirm it goes
# red, restore, confirm it goes green again.
#
# Each case uses its OWN COPY. Sharing a mutable file between cases caused a
# real defect in this repo before (project lesson 8th note 1, case 5) — the
# second case ran against the first case's leftovers and reported green on a
# case that should have been red.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECK="$ROOT/deploy/tests/check_compose_dangling_deps.py"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0

note() { printf '  %s\n' "$1"; }

# Run the check against an isolated sandbox directory.
# The checker resolves COMPOSE_FILES relative to CWD, so we run it from $WORK
# with a deploy/ tree that we fully control.
run_in_sandbox() {
  python3 "$CHECK" 2>&1
}

expect_rc() {
  local want="$1" desc="$2" dir="$3"
  local out rc
  out="$(cd "$dir" && run_in_sandbox)"
  rc=$?
  if [ "$rc" -eq "$want" ]; then
    note "PASS  $desc (rc=$rc)"
    pass=$((pass + 1))
  else
    note "FAIL  $desc (want rc=$want, got rc=$rc)"
    echo "$out" | sed 's/^/        /'
    fail=$((fail + 1))
  fi
}

# ---------------------------------------------------------------- case 0
# Baseline: a well-formed single-file compose must be green.
mkdir -p "$WORK/case0/deploy"
cat > "$WORK/case0/deploy/compose.yaml" <<'YAML'
services:
  alpha:
    image: busybox
    depends_on:
      beta:
        condition: service_started
  beta:
    image: busybox
YAML
expect_rc 0 "case0 well-formed: green" "$WORK/case0"

# ---------------------------------------------------------------- case 1
# The defect this guard exists for: depends_on points at a service that does
# not exist anywhere. podman-compose accepts this silently (rc=0 at config
# time, measured on 1.6.0), so only a structural check can catch it.
mkdir -p "$WORK/case1/deploy"
cat > "$WORK/case1/deploy/compose.yaml" <<'YAML'
services:
  alpha:
    image: busybox
    depends_on:
      ghost-service:
        condition: service_started
YAML
expect_rc 1 "case1 dangling dep: red" "$WORK/case1"

# ---------------------------------------------------------------- case 2
# Scanner must FAIL LOUDLY, not pass, when it finds zero services.
# This is the exact defect recorded in 5th lesson note 1 (module root pointed
# at a directory with no target/ -> every target SKIP -> exit 0).
mkdir -p "$WORK/case2/deploy"
cat > "$WORK/case2/deploy/compose.yaml" <<'YAML'
services: {}
YAML
expect_rc 2 "case2 empty services: scanner-broken, NOT green" "$WORK/case2"

# ---------------------------------------------------------------- case 3
# A file that exists but is not valid YAML must not be silently green either.
mkdir -p "$WORK/case3/deploy"
printf 'services:\n  alpha:\n   - [unclosed\n' > "$WORK/case3/deploy/compose.yaml"
expect_rc 2 "case3 malformed YAML: scanner-broken, NOT green" "$WORK/case3"

# ---------------------------------------------------------------- case 4
# Cross-file dependency is LEGAL and must stay green, otherwise splitting
# compose.yaml into compose.obs.yaml would be blocked by our own guard.
mkdir -p "$WORK/case4/deploy"
cat > "$WORK/case4/deploy/compose.yaml" <<'YAML'
services:
  alpha:
    image: busybox
    depends_on:
      beta:
        condition: service_started
YAML
cat > "$WORK/case4/deploy/compose.obs.yaml" <<'YAML'
services:
  beta:
    image: busybox
YAML
expect_rc 0 "case4 cross-file dep: legal, green" "$WORK/case4"

# ---------------------------------------------------------------- case 5
# Short-form list depends_on must also be scanned. If the scanner only handled
# the mapping form, this would be a false green.
mkdir -p "$WORK/case5/deploy"
cat > "$WORK/case5/deploy/compose.yaml" <<'YAML'
services:
  alpha:
    image: busybox
    depends_on:
      - ghost-shortform
YAML
expect_rc 1 "case5 short-form dangling: red" "$WORK/case5"

# ---------------------------------------------------------------- case 6
# The production config must be green RIGHT NOW. This is the real assertion,
# not a synthetic fixture.
mkdir -p "$WORK/case6"
cp -r "$ROOT/deploy" "$WORK/case6/deploy"
expect_rc 0 "case6 production config: green" "$WORK/case6"

echo
echo "negative-control suite: $pass passed, $fail failed"
[ "$fail" -eq 0 ]