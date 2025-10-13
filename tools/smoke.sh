#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT/build/reports/smoke"
mkdir -p "$REPORT_DIR"

section() {
  local name="$1"
  printf '\n===== %s =====\n' "$name"
}

cd "$ROOT"

section "Build (no tests)"
# Build artifacts but skip style gates; tests will run later explicitly
./gradlew clean assemble -x test -x ktlintCheck -x detekt

section "Unit tests"
./gradlew test

section "App module tests"
./gradlew :app-bot:test

section "Quality (non-blocking lint)"
# ktlint main/test checks (do not fail smoke)
./gradlew :app-bot:ktlintMainSourceSetCheck :app-bot:ktlintTestSourceSetCheck || true
# detekt (do not fail smoke)
./gradlew :app-bot:detekt || true

# stage artifacts (if exist)
KTLINT_TXT="$ROOT/app-bot/build/reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.txt"
DETEKT_XML="$ROOT/app-bot/build/reports/detekt/detekt.xml"
mkdir -p "$REPORT_DIR/quality"
[ -f "$KTLINT_TXT" ] && cp "$KTLINT_TXT" "$REPORT_DIR/quality/ktlint.txt"
[ -f "$DETEKT_XML" ] && cp "$DETEKT_XML" "$REPORT_DIR/quality/detekt.xml"

section "Report"
python3 "$ROOT/tools/smoke_report.py"
