import java.io.File;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import org.jetbrains.kotlin.abi.tools.api.AbiFilters;
import org.jetbrains.kotlin.abi.tools.api.AbiToolsFactory;
import org.jetbrains.kotlin.abi.tools.api.v2.AbiToolsV2;

class KroogJvmAbiCheck {
    // Match the exclusions used by the JVM and multiplatform Kotlin conventions.
    private static final AbiFilters FILTERS = new AbiFilters(Set.of(), Set.of(), Set.of(), Set.of(
        "ai.koog.agents.core.annotation.InternalAgentsApi",
        "ai.koog.prompt.annotations.InternalPromptAPI",
        "ai.koog.agents.core.tools.annotations.InternalAgentToolsApi",
        "ai.koog.prompt.executor.clients.InternalLLMClientApi",
        "ai.koog.prompt.structure.annotations.InternalStructuredOutputApi",
        "ai.koog.serialization.annotations.InternalKoogSerializationApi",
        "ai.koog.a2a.annotations.InternalA2AApi"
    ));

    public static void main(String[] args) {
        if (args.length == 0 || args.length % 3 != 0) {
            throw new IllegalArgumentException("Supply a coordinate, published JAR and current JAR for each comparison");
        }
        var reader = ServiceLoader.load(AbiToolsFactory.class).findFirst().orElseThrow().get().getV2();
        int incompatible = 0;
        for (int i = 0; i < args.length; i += 3) {
            var coordinate = args[i];
            var removed = signatures(reader, new File(args[i + 1]));
            removed.removeAll(signatures(reader, new File(args[i + 2])));
            if (removed.isEmpty()) {
                System.out.println("JVM ABI compatible with " + coordinate);
            } else {
                incompatible++;
                System.err.println("Published JVM ABI declarations removed or changed in " + coordinate);
                removed.stream().sorted().forEach(System.err::println);
            }
        }
        if (incompatible > 0) {
            throw new IllegalStateException("JVM ABI changed for " + incompatible + " stable publication(s)");
        }
    }

    private static Set<String> signatures(AbiToolsV2 reader, File jar) {
        var dump = new StringBuilder();
        reader.printJvmDump(dump, Set.of(jar), FILTERS);
        var result = new HashSet<String>();
        String owner = null;
        for (var line : dump.toString().split("\\R")) {
            if (line.isBlank() || line.equals("}")) {
                continue;
            }
            // Compare declarations by owning class, independent of dump ordering.
            if (line.endsWith(" {") && !Character.isWhitespace(line.charAt(0))) {
                owner = line;
                result.add(owner);
            } else if (owner != null && Character.isWhitespace(line.charAt(0))) {
                result.add(owner + " " + line.strip());
            } else {
                throw new IllegalStateException("Unexpected Kotlin JVM ABI declaration: " + line);
            }
        }
        return result;
    }
}
