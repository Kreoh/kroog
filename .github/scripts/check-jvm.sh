#!/usr/bin/env bash
set -euo pipefail

options=(--init-script gradle/kroog-ci.init.gradle --init-script gradle/kroog-affected-jvm.init.gradle
  --max-workers=4 -Pkotlin.compiler.execution.strategy=in-process)
if [[ ${1:-} == --full ]]; then
  shift
  task=:checkAllJvm
else
  base=HEAD
  if [[ ${1:-} == --base ]]; then
    base=${2:?Expected a commit after --base}
    shift 2
  fi
  mkdir -p build/ci
  manifest=$(mktemp "$PWD/build/ci/jvm-files.XXXXXXXX")
  trap 'rm -f -- "$manifest"' EXIT
  bash .github/scripts/changed-files.sh "$base" selector > "$manifest"
  if [[ -s "$manifest" ]]; then
    bash .github/scripts/test-affected-jvm.sh
  fi
  bash .github/scripts/changed-files.sh "$base" jvm > "$manifest"
  if [[ ! -s "$manifest" ]]; then
    echo "No JVM build or source inputs changed."
    exit 0
  fi
  task=:checkAffectedJvm
  options+=("-PkroogChangedFiles=$manifest")
fi

./gradlew "$task" "${options[@]}" "$@"
