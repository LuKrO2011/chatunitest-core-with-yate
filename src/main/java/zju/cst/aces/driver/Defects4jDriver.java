package zju.cst.aces.driver;

import zju.cst.aces.api.Task;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.Defects4jProject;
import zju.cst.aces.api.impl.RunnerImpl;
import zju.cst.aces.emit.RunDirEmitter;
import zju.cst.aces.measurement.FaultDetection;
import zju.cst.aces.measurement.MeasurementStage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Headless orchestration driver for the unified Java test-generation + measurement harness on
 * Defects4J. For each {@code (project, bug, modified-class)} it:
 *
 * <ol>
 *     <li>checks out the FIXED and BUGGY revisions and compiles both ({@code defects4j checkout} /
 *         {@code defects4j compile}),</li>
 *     <li>exports the classpaths / source + binary dirs / modified classes / revision ids
 *         ({@code defects4j export}),</li>
 *     <li>builds a headless {@link Config} over a {@link Defects4jProject} for the fixed checkout
 *         and generates tests with the chosen tool (LLM PhaseImpl pipeline, or EvoSuite),</li>
 *     <li>runs the uniform measurement stage (JaCoCo + PIT) and the fault-detection stage
 *         (fixed vs buggy), and</li>
 *     <li>emits the pynguin-analyser run directory.</li>
 * </ol>
 *
 * <p>Usage:</p>
 * <pre>
 * java -cp &lt;harness-jar-with-deps&gt; zju.cst.aces.driver.Defects4jDriver \
 *     --projects Lang,Cli --bugs 1-10,15 --tool plain --model gpt-4o-mini --seed 42 --out runs/
 * </pre>
 *
 * <p>LLM endpoint credentials are read from the environment: {@code LLM_BASE_URL},
 * {@code LLM_API_KEY}, {@code LLM_MODEL} (the {@code --model} flag overrides {@code LLM_MODEL}).</p>
 */
public class Defects4jDriver {

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        if (parsed == null) {
            printUsage();
            System.exit(2);
            return;
        }

        String baseUrl = env("LLM_BASE_URL", null);
        String apiKey = env("LLM_API_KEY", null);
        String model = parsed.model != null ? parsed.model : env("LLM_MODEL", null);

        boolean isEvoSuite = "evosuite".equalsIgnoreCase(parsed.tool);
        if (!isEvoSuite && (baseUrl == null || apiKey == null || model == null)) {
            System.err.println("LLM_BASE_URL, LLM_API_KEY and LLM_MODEL (or --model) must be set for tool "
                    + parsed.tool);
            System.exit(2);
            return;
        }
        if (parsed.yateEnv != null) {
            System.setProperty("chatunitest.yate.env", parsed.yateEnv);
        }

        Path outRoot = Paths.get(parsed.out);
        Files.createDirectories(outRoot);

        for (String project : parsed.projects) {
            for (int bug : parsed.bugs) {
                try {
                    runBug(project, bug, parsed, baseUrl, apiKey, model, isEvoSuite, outRoot);
                } catch (Exception e) {
                    System.err.println("Failed project=" + project + " bug=" + bug + ": " + e);
                    e.printStackTrace();
                }
            }
        }
    }

    private static void runBug(String project, int bug, Args parsed, String baseUrl, String apiKey,
                               String model, boolean isEvoSuite, Path outRoot) throws Exception {
        Path workRoot = Files.createTempDirectory("d4j-" + project + "-" + bug + "-");
        File fixedDir = workRoot.resolve("fixed").toFile();
        File buggyDir = workRoot.resolve("buggy").toFile();

        // 1. checkout fixed + buggy
        Defects4jCli.checkout(project, bug + "f", fixedDir);
        Defects4jCli.checkout(project, bug + "b", buggyDir);

        // 2. compile both
        Defects4jCli.compile(fixedDir);
        Defects4jCli.compile(buggyDir);

        // 3. export coordinates
        String cpTestFixed = Defects4jCli.export("cp.test", fixedDir);
        String cpTestBuggy = Defects4jCli.export("cp.test", buggyDir);
        String srcClasses = Defects4jCli.export("dir.src.classes", fixedDir);
        String binClassesFixed = Defects4jCli.export("dir.bin.classes", fixedDir);
        String binClassesBuggy = Defects4jCli.export("dir.bin.classes", buggyDir);
        String modifiedRaw = Defects4jCli.export("classes.modified", fixedDir);
        String buggyCommit = safeExport("revision.id.buggy", fixedDir);
        String fixedCommit = safeExport("revision.id.fixed", fixedDir);

        List<String> targets = splitClasses(modifiedRaw);
        if (parsed.targetClass != null) {
            // A single (project, bug, class) target was requested (SLURM-array granularity via the
            // Python defects4j_java_loop wrapper). Restrict to it so each array task emits exactly
            // one run dir, matching the evosuite-d4j path's per-target enumeration.
            if (targets.contains(parsed.targetClass)) {
                targets = List.of(parsed.targetClass);
            } else {
                System.err.println("Requested --target-class " + parsed.targetClass
                        + " is not among the modified classes " + targets + " for " + project + "-" + bug);
                return;
            }
        }
        if (targets.isEmpty()) {
            System.err.println("No modified classes for " + project + "-" + bug);
            return;
        }

        Path fixedSrcRoot = fixedDir.toPath().resolve(srcClasses);
        Path fixedBin = fixedDir.toPath().resolve(binClassesFixed);
        Path buggyBin = buggyDir.toPath().resolve(binClassesBuggy);

        String toolName = parsed.tool.toLowerCase() + "-d4j";
        String modelSlug = model == null ? "evosuite" : model.replaceAll("[^a-zA-Z0-9]", "");
        String configId = parsed.tool.toLowerCase() + "-class-" + modelSlug;
        String phaseType = toPhaseType(parsed.tool);

        for (String targetFqn : targets) {
            Path runDir = outRoot.resolve(project).resolve(String.valueOf(bug))
                    .resolve(targetFqn.replace('.', '_'));
            Files.createDirectories(runDir);

            // 4a. build headless config over the fixed checkout
            Defects4jProject d4jProject =
                    new Defects4jProject(fixedDir, srcClasses, binClassesFixed, cpTestFixed, project, String.valueOf(bug));
            List<String> classPaths = Defects4jProject.splitClassPath(cpTestFixed);

            // Order matters: set testOutput before classPaths/validatorTargetPath so the validator
            // captures the run-scoped paths, and set model before url (url() validates the model).
            Config.ConfigBuilder builder = new Config.ConfigBuilder(d4jProject)
                    .testOutput(runDir.resolve("generated-tests"))
                    .classPaths(classPaths)
                    .validatorTargetPath(runDir.resolve("validator-target"))
                    .phaseType(phaseType)
                    .stopWhenSuccess(true)
                    .temperature(0.1)               // paper's Plain-LLM setting for these runs
                    .pluginSign(toolName);

            if (!isEvoSuite) {
                // AskGPT POSTs directly to modelConfig.getUrl() (no path appending), and the
                // Model enum URLs are full chat-completions endpoints. LLM_BASE_URL is only the
                // OpenAI-compatible base (e.g. http://host:8001/v1), so append /chat/completions.
                String chatUrl = baseUrl.replaceAll("/+$", "");
                if (!chatUrl.endsWith("/chat/completions")) {
                    chatUrl = chatUrl + "/chat/completions";
                }
                builder.model(model).url(chatUrl).apiKeys(new String[]{apiKey});
            } else {
                // EvoSuite bypasses the LLM path; a nominal model keeps ConfigBuilder happy, and
                // merging is disabled so the EvoSuite suite is left as emitted.
                builder.model(env("LLM_MODEL", "gpt-4o-mini")).enableMerge(false);
            }

            Config config = builder.build();

            // 4b. generation
            try {
                if (!isEvoSuite) {
                    config.print(); // initialises Yate (ConfigYate) + PromptService for the LLM path
                }
            } catch (Throwable t) {
                config.getLogger().warn("Config.print/Yate init failed (continuing): " + t);
            }

            try {
                Task task = new Task(config, new RunnerImpl(config));
                task.startClassTask(targetFqn);
            } catch (Throwable t) {
                config.getLogger().error("Generation failed for " + targetFqn + ": " + t);
            }

            // 5. emit base run dir (tests, sources, manifests)
            new RunDirEmitter(runDir).emit(toolName, project, String.valueOf(bug), targetFqn, configId,
                    parsed.seed, buggyCommit, fixedCommit, /*bugDiff*/ "", /*llmQueryCount*/ 0,
                    config.getTestOutput(), fixedSrcRoot);

            // 6. uniform measurement (JaCoCo + PIT)
            new MeasurementStage(config, runDir, targetFqn, fixedSrcRoot, fixedBin).run();

            // 7. fault detection (fixed vs buggy)
            List<String> harnessCp = harnessClasspath();
            List<String> fixedCp = combine(classPaths, fixedBin.toAbsolutePath().toString(), harnessCp);
            List<String> buggyCp = combine(Defects4jProject.splitClassPath(cpTestBuggy),
                    buggyBin.toAbsolutePath().toString(), harnessCp);
            new FaultDetection(config, runDir, fixedCp, buggyCp).run();

            config.getLogger().info("Completed run dir: " + runDir);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String toPhaseType(String tool) {
        switch (tool.toLowerCase()) {
            case "evosuite":
                return "EVOSUITE";
            case "plain":
                // Not a PhaseType enum value -> falls through to the default MethodRunner + Yate path.
                return "PLAIN";
            default:
                // coverup -> COVERUP, hits -> HITS, symprompt -> SYMPROMPT, chattester -> CHATTESTER, ...
                return tool.toUpperCase();
        }
    }

    private static String safeExport(String property, File workDir) {
        try {
            return Defects4jCli.export(property, workDir);
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> splitClasses(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,\\s]+")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> harnessClasspath() {
        String cp = System.getProperty("java.class.path");
        if (cp == null) {
            return new ArrayList<>();
        }
        String sep = System.getProperty("path.separator", ":");
        return new ArrayList<>(Arrays.asList(cp.split(java.util.regex.Pattern.quote(sep))));
    }

    private static List<String> combine(List<String> cpEntries, String binDir, List<String> harnessCp) {
        Set<String> merged = new LinkedHashSet<>(cpEntries);
        merged.add(binDir);
        merged.addAll(harnessCp);
        return new ArrayList<>(merged);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static void printUsage() {
        System.err.println("Usage: Defects4jDriver --projects Lang,Cli --bugs 1-10,15 "
                + "--tool plain --model gpt-4o-mini --seed 42 --out runs/ "
                + "[--target-class org.apache.commons.lang3.Foo] [--yate-env path/.env.chatunitest]");
        System.err.println("Env: LLM_BASE_URL, LLM_API_KEY, LLM_MODEL (required for LLM tools); "
                + "EVOSUITE_JAR (evosuite); PIT_CLASSPATH (optional).");
    }

    /** Parsed command-line arguments. */
    static final class Args {
        List<String> projects = new ArrayList<>();
        List<Integer> bugs = new ArrayList<>();
        String tool = "plain";
        String model;
        long seed = 42L;
        String out = "runs";
        String yateEnv;
        String targetClass;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String key = argv[i];
                String val = (i + 1 < argv.length) ? argv[++i] : null;
                switch (key) {
                    case "--projects": a.projects = Arrays.asList(val.split(",")); break;
                    case "--bugs":     a.bugs = parseRanges(val); break;
                    case "--tool":     a.tool = val; break;
                    case "--model":    a.model = val; break;
                    case "--seed":     a.seed = Long.parseLong(val); break;
                    case "--out":      a.out = val; break;
                    case "--yate-env": a.yateEnv = val; break;
                    case "--target-class": a.targetClass = val; break;
                    default:
                        System.err.println("Unknown argument: " + key);
                        return null;
                }
            }
            if (a.projects.isEmpty() || a.bugs.isEmpty()) {
                return null;
            }
            return a;
        }

        /** Parse a range spec like {@code "1-10,15,20-22"} into an explicit list of ids. */
        static List<Integer> parseRanges(String spec) {
            List<Integer> result = new ArrayList<>();
            if (spec == null) {
                return result;
            }
            for (String token : spec.split(",")) {
                token = token.trim();
                if (token.isEmpty()) {
                    continue;
                }
                if (token.contains("-")) {
                    String[] bounds = token.split("-");
                    int lo = Integer.parseInt(bounds[0].trim());
                    int hi = Integer.parseInt(bounds[1].trim());
                    for (int i = lo; i <= hi; i++) {
                        result.add(i);
                    }
                } else {
                    result.add(Integer.parseInt(token));
                }
            }
            return result;
        }
    }
}
