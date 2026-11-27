#!/usr/bin/env bash
set -euo pipefail
base=${1:-origin/master}
task=${2:-ktlintCheck}
[[ "$task" == ktlintCheck || "$task" == ktlintFormat ]]
mkdir -p build/ci
bash .github/scripts/changed-files.sh "$base" kotlin > build/ci/lint-files
if [[ ! -s build/ci/lint-files ]]; then
  echo "No changed Kotlin files."
  exit 0
fi
./gradlew ":${task}Changed" --init-script gradle/kroog-lint.init.gradle \
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process
