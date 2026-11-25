#!/usr/bin/env bash
set -euo pipefail
base=${1:-origin/master}
task=${2:-ktlintCheck}
[[ "$task" == ktlintCheck || "$task" == ktlintFormat ]]
base=$(git rev-parse --verify --end-of-options "${base}^{commit}")
mkdir -p build/ci
git diff --name-only -z --diff-filter=ACMR "$base" -- '*.kt' '*.kts' > build/ci/lint-files
git ls-files --others --exclude-standard -z -- '*.kt' '*.kts' >> build/ci/lint-files
./gradlew "$task" --init-script gradle/kroog-lint.init.gradle \
  --max-workers=2 --no-daemon -Pkotlin.compiler.execution.strategy=in-process
