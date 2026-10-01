---
search:
exclude: true
---

# --8<-- [start:prerequisites]
Ensure your environment and project meet the following requirements:

- JDK 17+
- Kotlin 2.3.10+
- Gradle 8.0+ or Maven 3.8+
# --8<-- [end:prerequisites]

# --8<-- [start:dependencies]
Add the Kroog JVM dependencies below. These examples use confirmed published revision 15. The current source prepares `1.3.0-kroog.1`, which is unpublished. For snapshot repositories and POM-based Gradle resolution, follow the Kroog snapshot note in [Quickstart](../quickstart.md).

=== "Gradle (Kotlin)"

    ``` kotlin title="build.gradle.kts"
    dependencies {
        // Stable
        implementation("com.kreoh.kroog:koog-agents-jvm:1.1.1-kroog.15")

        // Beta
        implementation("com.kreoh.kroog:koog-agents-additions-jvm:1.1.1-beta-kroog.15")
    }
    ```

=== "Gradle (Groovy)"

    ``` groovy title="build.gradle"
    dependencies {
        // Stable
        implementation 'com.kreoh.kroog:koog-agents-jvm:1.1.1-kroog.15'

        // Beta
        implementation 'com.kreoh.kroog:koog-agents-additions-jvm:1.1.1-beta-kroog.15'
    }
    ```

=== "Maven"

    ```xml title="pom.xml"
    <dependencies>
        <!-- Stable -->
        <dependency>
            <groupId>com.kreoh.kroog</groupId>
            <artifactId>koog-agents-jvm</artifactId>
            <version>1.1.1-kroog.15</version>
        </dependency>

        <!-- Beta -->
        <dependency>
            <groupId>com.kreoh.kroog</groupId>
            <artifactId>koog-agents-additions-jvm</artifactId>
            <version>1.1.1-beta-kroog.15</version>
        </dependency>
    </dependencies>
    ```
# --8<-- [end:dependencies]

# --8<-- [start:api-key]
Get an API key from an LLM provider or run a local LLM via Ollama.
For more information, see [Quickstart](../quickstart.md).
# --8<-- [end:api-key]

