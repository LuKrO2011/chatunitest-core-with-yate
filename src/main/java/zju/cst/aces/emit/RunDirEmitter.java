package zju.cst.aces.emit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Writes the self-describing run directory that pynguin-analyser's Defects4J importers ingest.
 * The layout mirrors the existing {@code evosuite-d4j} flow so {@code analyse-loop},
 * {@code merge}, {@code summarise} and {@code rq_tables} are unchanged:
 *
 * <pre>
 * &lt;runDir&gt;/
 *   d4j-info.json                 (primary detection marker + all run metadata)
 *   statistics.csv                (single data row; header matches the `statistics` table)
 *   evosuite-tests/&lt;pkg&gt;/*.java  (generated test sources — dir name kept for detection)
 *   sources/&lt;pkg&gt;/&lt;Class&gt;.java  (SUT source, for `sut_content` in the qualitative markdown)
 *   coverage/jacoco.xml           (written by MeasurementStage)
 *   mutation/mutations.xml        (written by MeasurementStage)
 *   bug-detection/bug_detection.csv (written by FaultDetection)
 *   logs/                         (per-run logs)
 * </pre>
 */
public class RunDirEmitter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path runDir;

    public RunDirEmitter(Path runDir) {
        this.runDir = runDir;
    }

    /**
     * @param toolName      e.g. {@code plain-d4j} / {@code evosuite-d4j}
     * @param project       Defects4J project id (e.g. {@code Lang})
     * @param bugId         Defects4J bug id (e.g. {@code 1})
     * @param targetModule  fully-qualified target class name
     * @param configId      e.g. {@code plain-class-gpt4omini}
     * @param seed          random seed used for the run
     * @param buggyCommit   {@code revision.id.buggy}
     * @param fixedCommit   {@code revision.id.fixed}
     * @param bugDiff       textual diff between buggy and fixed (may be empty)
     * @param llmQueryCount number of LLM queries issued (0 for EvoSuite)
     * @param testOutput    directory holding the generated {@code *.java} test sources
     * @param srcRoot       source root of the checkout containing the target class
     */
    public void emit(String toolName, String project, String bugId, String targetModule, String configId,
                     long seed, String buggyCommit, String fixedCommit, String bugDiff, int llmQueryCount,
                     Path testOutput, Path srcRoot) throws IOException {
        Files.createDirectories(runDir);
        Files.createDirectories(runDir.resolve("logs"));

        String pkgPath = packagePath(targetModule);
        String simpleName = simpleName(targetModule);
        String sutRelative = "sources/" + (pkgPath.isEmpty() ? "" : pkgPath + "/") + simpleName + ".java";
        String runId = project + "-" + bugId + "-" + targetModule + "-" + configId;

        copyTests(testOutput);
        copySut(srcRoot, targetModule, pkgPath, simpleName);
        writeD4jInfo(toolName, project, bugId, targetModule, configId, seed, buggyCommit, fixedCommit,
                bugDiff, sutRelative, runId);
        writeStatistics(runId, targetModule, configId, project, seed, llmQueryCount);
    }

    private void writeD4jInfo(String toolName, String project, String bugId, String targetModule, String configId,
                              long seed, String buggyCommit, String fixedCommit, String bugDiff,
                              String sutRelative, String runId) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("tool_name", toolName);
        info.put("project", project);
        info.put("bug_id", bugId);
        info.put("target_module", targetModule);
        info.put("config_id", configId);
        info.put("seed", seed);
        info.put("buggy_commit", buggyCommit);
        info.put("fixed_commit", fixedCommit);
        info.put("sut_file", sutRelative);
        info.put("run_id", runId);
        info.put("bug_diff", bugDiff == null ? "" : bugDiff);
        try (Writer w = new OutputStreamWriter(
                Files.newOutputStream(runDir.resolve("d4j-info.json")), StandardCharsets.UTF_8)) {
            w.write(GSON.toJson(info));
        }
    }

    private void writeStatistics(String runId, String targetModule, String configId, String project,
                                 long seed, int llmQueryCount) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(runDir.resolve("statistics.csv"), StandardCharsets.UTF_8)) {
            w.write("RunId,TargetModule,ConfigurationId,ProjectName,RandomSeed,llm_query_count");
            w.newLine();
            w.write(String.join(",",
                    csv(runId), csv(targetModule), csv(configId), csv(project),
                    Long.toString(seed), Integer.toString(llmQueryCount)));
            w.newLine();
        }
    }

    private void copyTests(Path testOutput) throws IOException {
        Path dest = runDir.resolve("evosuite-tests");
        Files.createDirectories(dest);
        if (testOutput == null || !Files.exists(testOutput)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(testOutput)) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                Path rel = testOutput.relativize(p);
                Path target = dest.resolve(rel.toString());
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void copySut(Path srcRoot, String targetModule, String pkgPath, String simpleName) throws IOException {
        Path dest = runDir.resolve("sources")
                .resolve(pkgPath.isEmpty() ? "" : pkgPath)
                .resolve(simpleName + ".java");
        Files.createDirectories(dest.getParent());
        if (srcRoot == null) {
            return;
        }
        Path sutSource = srcRoot.resolve(targetModule.replace('.', '/') + ".java");
        if (Files.exists(sutSource)) {
            Files.copy(sutSource, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String packagePath(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot).replace('.', '/');
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** Minimal CSV escaping for values that might contain a comma or quote. */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
