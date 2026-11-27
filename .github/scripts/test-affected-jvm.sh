#!/usr/bin/env bash
set -euo pipefail

repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
cd "$fixture"

mkdir -p .github/scripts gradle
cp "$repository"/.github/scripts/{check-jvm,changed-files}.sh .github/scripts/
cp "$repository"/gradle/{kroog-ci,kroog-affected-jvm}.init.gradle gradle/
ln -s "$repository/gradlew" gradlew
printf '%s\n' .gradle/ build/ > .gitignore

projects=(library middle app test-client test-client-consumer integration-tests test-utils skills-api-consumer-test)
mkdir "${projects[@]}"
{
  echo "pluginManagement { includeBuild('convention-plugin-ai') }"
  echo "rootProject.name = 'test-affected-jvm'"
  printf "include '%s'\n" "${projects[@]}"
} > settings.gradle
cat > build.gradle <<'GRADLE'
subprojects {
    configurations {
        compileClasspath
        runtimeClasspath
        testCompileClasspath
        testRuntimeClasspath
    }
    def marker = rootProject.layout.buildDirectory.file("observed/${providers.gradleProperty('scenario').get()}/${name}")
    def tasksByProject = ['skills-api-consumer-test': 'compileKotlin', 'test-client': 'test']
    tasks.register(tasksByProject.getOrDefault(name, 'jvmTest')) {
        doLast {
            marker.get().asFile.with {
                parentFile.mkdirs()
                text = 'ran'
            }
        }
    }
}

project(':middle').dependencies.add('compileClasspath', project(':library'))
project(':app').dependencies.add('runtimeClasspath', project(':middle'))
project(':test-client').dependencies.add('testRuntimeClasspath', project(':library'))
project(':test-client-consumer').dependencies.add('compileClasspath', project(':test-client'))
project(':skills-api-consumer-test').dependencies.add('compileClasspath', project(':library'))
GRADLE

mkdir -p convention-plugin-ai/src/test/resources
echo "rootProject.name = 'convention-plugin-ai'" > convention-plugin-ai/settings.gradle
cat > convention-plugin-ai/build.gradle <<'GRADLE'
tasks.register('test') {
    doLast {
        def marker = file("../build/observed/${providers.gradleProperty('scenario').get()}/convention-plugin-ai")
        marker.parentFile.mkdirs()
        marker.text = 'ran'
    }
}
GRADLE

commit_fixture() {
  git add --all
  git -c core.hooksPath=/dev/null -c user.name=Fixture -c user.email=fixture@example.invalid \
    commit --quiet --no-gpg-sign -m 'test: update selection fixture'
}

expect_tasks() {
  local scenario=$1 expected actual directory
  shift
  directory="build/observed/$scenario"
  expected=$(printf '%s\n' "$@" | LC_ALL=C sort)
  actual=
  if [[ -d "$directory" ]]; then
    actual=$(cd "$directory" && printf '%s\n' * | LC_ALL=C sort)
  fi
  if [[ "$actual" != "$expected" ]]; then
    printf 'Wrong task selection for %s\nExpected:\n%s\nObserved:\n%s\n' "$scenario" "$expected" "$actual" >&2
    exit 1
  fi
}

git init --quiet --template=
mkdir -p library/src/main/java library/src/test/resources
echo 'class Library {}' > library/src/main/java/Library.java
echo 'Original test resource' > library/src/test/resources/example.md
commit_fixture

echo 'Edited documentation' > README.md
bash .github/scripts/check-jvm.sh --base HEAD --offline -Pscenario=docs
expect_tasks docs
[[ ! -d .gradle ]] || { echo 'Documentation started Gradle.' >&2; exit 1; }
commit_fixture

echo 'Edited test resource' > library/src/test/resources/example.md
bash .github/scripts/check-jvm.sh --base HEAD --offline -Pscenario=test-resource
expect_tasks test-resource library
commit_fixture

echo 'Convention test data' > convention-plugin-ai/src/test/resources/input.txt
bash .github/scripts/check-jvm.sh --base HEAD --offline -Pscenario=convention-test
expect_tasks convention-test convention-plugin-ai
commit_fixture

rm -- library/src/main/java/Library.java
bash .github/scripts/check-jvm.sh --base HEAD --offline -Pscenario=production
expect_tasks production library middle app test-client skills-api-consumer-test

echo 'org.gradle.parallel=true' > gradle.properties
bash .github/scripts/check-jvm.sh --base HEAD --offline -Pscenario=shared-build
expect_tasks shared-build "${projects[@]}" convention-plugin-ai

bash .github/scripts/check-jvm.sh --full --offline -Pscenario=full
expect_tasks full "${projects[@]}" convention-plugin-ai

echo 'Affected JVM selection passed.'
