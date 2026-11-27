#!/usr/bin/env bash
set -euo pipefail

base=$(git rev-parse --verify --end-of-options "${1:?Expected a base commit}^{commit}")
diff_options=(--no-renames)
case "${2:?Expected a change scope}" in
  kotlin)
    paths=('*.kt' '*.kts')
    diff_options+=(--diff-filter=ACMT)
    ;;
  jvm)
    # Include deleted inputs and both sides of moves so their former owners are tested.
    paths=(':(glob)**/src/**' '*.gradle' '*.gradle.kts' ':(glob)**/gradle.properties'
      gradlew gradlew.bat gradle a2a/test-python-a2a-server
      ':(top,exclude)examples' ':(top,exclude)docs' ':(top,exclude)gradle/kroog-lint.init.gradle'
      ':(top,exclude)gradle/kroog-affected-jvm.init.gradle' ':(top,exclude)gradle/kroog-jvm-publications.txt')
    ;;
  selector)
    paths=(.github/scripts/changed-files.sh .github/scripts/check-jvm.sh
      .github/scripts/test-affected-jvm.sh gradle/kroog-affected-jvm.init.gradle)
    ;;
  *) echo "Unknown change scope: $2" >&2; exit 2 ;;
esac

git diff --name-only -z "${diff_options[@]}" "$base" -- "${paths[@]}"
git ls-files --others --exclude-standard -z -- "${paths[@]}"
