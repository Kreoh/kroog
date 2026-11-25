#!/usr/bin/env bash
set -euo pipefail

# Use the release inventory so every shipped JVM module is compiled and tested.
tasks=()
while read -r kind project extra; do
  [[ -z "${extra:-}" && "$project" =~ ^(:[a-z0-9][a-z0-9-]*)+$ ]]
  case "$kind" in
    jvm) tasks+=("$project:jvmTest" "$project:jvmJar") ;;
    maven) tasks+=("$project:test" "$project:jar") ;;
    *) echo "Unsupported publication kind: $kind" >&2; exit 1 ;;
  esac
done < gradle/kroog-jvm-publications.txt
[[ ${#tasks[@]} -gt 0 ]]
tasks+=(":integration-tests:jvmTest")
./gradlew "${tasks[@]}" --init-script gradle/kroog-ci.init.gradle --max-workers=2 --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process "$@"
