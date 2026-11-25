import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.filter.BehaviorFilter;
import japicmp.filter.ClassFilter;
import japicmp.filter.FieldFilter;
import japicmp.model.AccessModifier;
import japicmp.model.JApiClass;
import japicmp.model.JApiCompatibilityChangeType;
import japicmp.model.JApiSemanticVersionLevel;
import japicmp.output.stdout.StdoutOutputGenerator;
import javassist.CtBehavior;
import javassist.Modifier;
import javassist.bytecode.AccessFlag;
import org.jetbrains.kotlin.abi.tools.api.AbiFilters;
import org.jetbrains.kotlin.abi.tools.api.AbiToolsFactory;
import org.jetbrains.kotlin.abi.tools.api.v2.AbiToolsV2;

class KroogJvmAbiCheck {
    private static final Pattern CLASS = Pattern.compile(".*? class (.+?)(?: : .+)? \\{");
    private static final Pattern MEMBER = Pattern.compile("\\s+.*? (fun|field) (.+) (\\S+)");

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

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Supply the path to the list of JVM publication comparisons");
        }
        var rows = Files.readAllLines(Path.of(args[0]));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No JVM publication comparisons were supplied");
        }
        var reader = ServiceLoader.load(AbiToolsFactory.class).findFirst().orElseThrow().get().getV2();
        var publicApiByArchive = new HashMap<File, Map<String, Set<String>>>();
        int incompatible = 0;
        for (var row : rows) {
            var columns = row.split("\\t", -1);
            if (columns.length != 6) {
                throw new IllegalArgumentException("Expected a coordinate, two JARs and three classpaths per comparison");
            }
            var coordinate = columns[0];
            var previous = new File(columns[1]);
            var current = new File(columns[2]);
            var report = compare(reader, publicApiByArchive, previous, current, columns[3], columns[4], columns[5]);
            if (report.isEmpty()) {
                System.out.println("JVM ABI compatible with " + coordinate);
            } else {
                incompatible++;
                System.err.println("Incompatible JVM ABI changes in " + coordinate);
                System.err.print(report);
            }
        }
        if (incompatible > 0) {
            throw new IllegalStateException("JVM ABI changed for " + incompatible + " stable publication(s)");
        }
    }

    private static String compare(
        AbiToolsV2 reader, Map<File, Map<String, Set<String>>> publicApiByArchive,
        File previous, File current, String previousClasspath, String currentClasspath, String apiDependencies
    ) {
        var api = new HashMap<String, Set<String>>();
        addPublicApi(api, publicApiByArchive.computeIfAbsent(previous, jar -> readPublicApi(reader, jar)));
        addPublicApi(api, publicApiByArchive.computeIfAbsent(current, jar -> readPublicApi(reader, jar)));
        var comparedClasses = Set.copyOf(api.keySet());
        // Inherited members from Kroog modules have the same visibility rules as members
        // declared in the compared JAR. Keep other dependencies available for JVM lookup.
        if (!apiDependencies.isEmpty()) {
            for (var path : apiDependencies.split(Pattern.quote(File.pathSeparator))) {
                addPublicApi(api, publicApiByArchive.computeIfAbsent(new File(path), jar -> readPublicApi(reader, jar)));
            }
        }

        var previousArchive = new JApiCmpArchive(previous, "");
        var currentArchive = new JApiCmpArchive(current, "");
        var options = Options.newDefault();
        options.setOldArchives(List.of(previousArchive));
        options.setNewArchives(List.of(currentArchive));
        options.setAccessModifier(AccessModifier.PROTECTED);
        // Kotlin default arguments and JVM bridges can be called from previously compiled code.
        options.setIncludeSynthetic(true);
        options.setOutputOnlyBinaryIncompatibleModifications(true);

        var comparison = JarArchiveComparatorOptions.of(options);
        comparison.setClassPathMode(JarArchiveComparatorOptions.ClassPathMode.TWO_SEPARATE_CLASSPATHS);
        // Keep excluded types in the lookup classpath so japicmp can still resolve their members.
        comparison.setOldClassPath(classpath(previous, previousClasspath));
        comparison.setNewClassPath(classpath(current, currentClasspath));
        var filters = comparison.getFilters().getExcludes();
        filters.add((ClassFilter) type -> !comparedClasses.contains(type.getName()));
        filters.add((BehaviorFilter) member -> isFinalClassSerializationConstructor(member)
            || isOutsidePublicApi(api, member.getDeclaringClass().getName(),
                "fun " + member.getMethodInfo2().getName() + " " + member.getSignature()));
        filters.add((FieldFilter) field -> isOutsidePublicApi(api, field.getDeclaringClass().getName(),
            "field " + field.getName() + " " + field.getSignature()));

        // The JLS permits these additions with old callers. New code calling the abstract
        // method on an old third-party implementation can throw AbstractMethodError.
        for (var change : List.of(JApiCompatibilityChangeType.METHOD_ADDED_TO_INTERFACE,
                                  JApiCompatibilityChangeType.METHOD_ABSTRACT_ADDED_TO_CLASS)) {
            comparison.addOverrideCompatibilityChange(new JarArchiveComparatorOptions.OverrideCompatibilityChange(
                change, false, false, JApiSemanticVersionLevel.MAJOR));
        }
        var changes = new JarArchiveComparator(comparison).compare(previousArchive, currentArchive);
        if (changes.stream().allMatch(JApiClass::isBinaryCompatible)) {
            return "";
        }
        var classChanges = changes.stream().flatMap(type -> type.getCompatibilityChanges().stream()
            .filter(change -> !change.isBinaryCompatible())
            .map(change -> type.getFullyQualifiedName() + " " + change.getType() + System.lineSeparator()))
            .collect(Collectors.joining());
        return new StdoutOutputGenerator(options, changes).generate() + classChanges;
    }

    private static List<String> classpath(File jar, String dependencies) {
        return Arrays.asList((jar.getAbsolutePath() + File.pathSeparator + dependencies)
            .split(Pattern.quote(File.pathSeparator)));
    }

    private static boolean isFinalClassSerializationConstructor(CtBehavior member) {
        // The generated serializer calls this synthetic constructor. A final class cannot
        // have child serializers in downstream modules that call it from a subclass.
        return member.getMethodInfo2().isConstructor()
            && (member.getMethodInfo2().getAccessFlags() & AccessFlag.SYNTHETIC) != 0
            && Modifier.isFinal(member.getDeclaringClass().getModifiers())
            && member.getSignature().endsWith("Lkotlinx/serialization/internal/SerializationConstructorMarker;)V");
    }

    private static boolean isOutsidePublicApi(Map<String, Set<String>> api, String owner, String member) {
        var members = api.get(owner);
        // Class comparison excludes non-API types. Keep their members available for inheritance.
        return members != null && !members.contains(member);
    }

    private static void addPublicApi(Map<String, Set<String>> api, Map<String, Set<String>> additionalApi) {
        additionalApi.forEach((owner, members) -> api.merge(owner, members, (previous, current) -> {
            if (previous.equals(current)) {
                return previous;
            }
            var merged = new HashSet<>(previous);
            merged.addAll(current);
            return merged;
        }));
    }

    private static Map<String, Set<String>> readPublicApi(AbiToolsV2 reader, File jar) {
        var dump = new StringBuilder();
        reader.printJvmDump(dump, Set.of(jar), FILTERS);
        var api = new HashMap<String, Set<String>>();
        String owner = null;
        for (var line : dump.toString().lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            if (line.equals("}")) {
                owner = null;
                continue;
            }
            var type = CLASS.matcher(line);
            var member = MEMBER.matcher(line);
            // The Kotlin tool determines visibility. Only extract JVM names and descriptors.
            if (owner == null && type.matches()) {
                owner = type.group(1).replace('/', '.');
                api.computeIfAbsent(owner, unused -> new HashSet<>());
            } else if (owner != null && member.matches()) {
                api.get(owner).add(member.group(1) + " " + member.group(2) + " " + member.group(3));
            } else {
                throw new IllegalStateException("Unexpected Kotlin JVM ABI declaration in " + jar + ": " + line);
            }
        }
        return api;
    }
}
