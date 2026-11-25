#!/usr/bin/env bash
set -euo pipefail

jdk=$1
classpath=$2
checker="$(dirname "$0")/KroogJvmAbiCheck.java"
fixtures="$(mktemp -d)"
trap 'rm -rf -- "$fixtures"' EXIT

mkdir -p "$fixtures"/{previous,opened,abstract,removed}
cat > "$fixtures/InternalPromptAPI.java" <<'EOF'
package ai.koog.prompt.annotations;

public @interface InternalPromptAPI {}
EOF
cat > "$fixtures/previous/Dependency.java" <<'EOF'
import ai.koog.prompt.annotations.InternalPromptAPI;

public class Dependency {
    public void preserved() {}

    @InternalPromptAPI
    public void implementationDetail() {}
}
EOF
cat > "$fixtures/opened/Dependency.java" <<'EOF'
public class Dependency {
    public void inherited() {}
    public void preserved() {}
}
EOF
cat > "$fixtures/removed/Dependency.java" <<'EOF'
public class Dependency {
    public void inherited() {}
}
EOF
cat > "$fixtures/previous/Library.java" <<'EOF'
public final class Library extends Dependency {
    public void inherited() {}

    public interface Extension {
        void run();
    }
}
EOF
cat > "$fixtures/opened/Library.java" <<'EOF'
public class Library extends Dependency {
    public interface Extension {
        void run();
        default void reset() {}
    }
}
EOF
cat > "$fixtures/abstract/Library.java" <<'EOF'
public final class Library extends Dependency {
    public void inherited() {}

    public interface Extension {
        void run();
        void reset();
    }
}
EOF

for version in previous opened removed; do
    "$jdk/bin/javac" -d "$fixtures/$version/dependency" \
        "$fixtures/$version/Dependency.java" "$fixtures/InternalPromptAPI.java"
    "$jdk/bin/jar" --create --file "$fixtures/$version-dependency.jar" -C "$fixtures/$version/dependency" .
done
cp "$fixtures/previous-dependency.jar" "$fixtures/abstract-dependency.jar"
for version in previous opened abstract; do
    "$jdk/bin/javac" -classpath "$fixtures/$version-dependency.jar" \
        -d "$fixtures/$version/classes" "$fixtures/$version/Library.java"
    "$jdk/bin/jar" --create --file "$fixtures/$version.jar" -C "$fixtures/$version/classes" .
done

check() {
    local previous_dependency="$fixtures/previous-dependency.jar"
    local current_dependency="$fixtures/$2-dependency.jar"
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' fixture "$fixtures/previous.jar" "$fixtures/$1.jar" \
        "$previous_dependency" "$current_dependency" "$previous_dependency:$current_dependency" > "$fixtures/comparison.tsv"
    "$jdk/bin/java" -classpath "$classpath" "$checker" "$fixtures/comparison.tsv"
}

reject() {
    local report
    if report="$(check "$1" "$2" 2>&1)"; then
        echo "The checker accepted the incompatible $3 fixture." >&2
        exit 1
    fi
    # Compilation failures and invalid fixture JARs cannot satisfy this regression.
    if [[ "$report" != *"$3"* ]]; then
        printf '%s\n' "$report" >&2
        exit 1
    fi
}

check opened opened
reject abstract abstract 'reset()'
reject opened removed 'METHOD_REMOVED_IN_SUPERCLASS'
