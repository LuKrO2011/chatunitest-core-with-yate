package zju.cst.aces.runner.solution_runner;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.runner.MethodRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sibling test-generation runner that shells out to the EvoSuite standalone jar instead of the
 * LLM {@link zju.cst.aces.api.phase.PhaseImpl} pipeline. It is selected when
 * {@code config.getPhaseType()} equals {@code "EVOSUITE"} (see
 * {@link zju.cst.aces.runner.ClassRunner#selectRunner} and
 * {@link zju.cst.aces.api.impl.RunnerImpl#selectRunner}).
 *
 * <p>EvoSuite generates a whole test suite for a class in a single invocation, so — even though
 * ChatUniTest drives runners per-method — this runner only actually launches EvoSuite once per
 * fully-qualified class name (guarded by {@link #GENERATED}). Subsequent per-method calls are
 * no-ops.</p>
 *
 * <p>The EvoSuite jar path is read from the {@code EVOSUITE_JAR} environment variable. Generated
 * JUnit sources are written under {@code config.getTestOutput()} via EvoSuite's {@code test_dir}
 * property. EvoSuite emits JUnit 4 tests, which the downstream measurement / fault-detection
 * stages run through the JUnit Platform vintage engine.</p>
 */
public class EvoSuiteRunner extends MethodRunner {

    /** Fully-qualified class names for which EvoSuite has already been invoked in this JVM. */
    private static final Set<String> GENERATED = Collections.synchronizedSet(new HashSet<>());

    /** Default EvoSuite search budget (seconds) when {@code EVOSUITE_SEARCH_BUDGET} is unset. */
    private static final String DEFAULT_SEARCH_BUDGET = "60";

    public EvoSuiteRunner(Config config, String fullClassName, MethodInfo methodInfo) throws IOException {
        // Extend MethodRunner (like the other solution_runner siblings) so this class can legally
        // override the public start(); AbstractRunner.start() is package-private and not overridable
        // from this sub-package. MethodRunner stores methodInfo; start() is replaced wholesale below
        // so none of the LLM per-method generation logic runs.
        super(config, fullClassName, methodInfo);
    }

    @Override
    public void start() throws IOException {
        // EvoSuite is class-level: only run once per class regardless of how many methods route here.
        if (!GENERATED.add(fullClassName)) {
            return;
        }
        runEvoSuite();
    }

    private void runEvoSuite() throws IOException {
        String evosuiteJar = System.getenv("EVOSUITE_JAR");
        if (evosuiteJar == null || evosuiteJar.isBlank()) {
            throw new IOException("EVOSUITE_JAR environment variable is not set; cannot run EvoSuite for "
                    + fullClassName);
        }
        File jarFile = new File(evosuiteJar);
        if (!jarFile.exists()) {
            throw new IOException("EvoSuite jar not found at EVOSUITE_JAR=" + evosuiteJar);
        }

        Path testOutput = config.getTestOutput();
        testOutput.toFile().mkdirs();

        String projectCP = buildProjectClassPath();
        String searchBudget = System.getenv().getOrDefault("EVOSUITE_SEARCH_BUDGET", DEFAULT_SEARCH_BUDGET);

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(jarFile.getAbsolutePath());
        command.add("-class");
        command.add(fullClassName);
        command.add("-projectCP");
        command.add(projectCP);
        command.add("-Dtest_dir=" + testOutput.toAbsolutePath());
        command.add("-Dsearch_budget=" + searchBudget);
        // Emit compilable JUnit sources only; suppress scaffolding to keep the emitted suite flat.
        command.add("-Djunit_check=false");
        command.add("-Duse_separate_classloader=false");

        config.getLogger().info(String.format("[%s] Running EvoSuite for class < %s >",
                config.pluginSign, fullClassName));
        config.getLogger().debug("EvoSuite command: " + String.join(" ", command));

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(config.getProject().getBasedir());
            processBuilder.inheritIO();
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                config.getLogger().warn("EvoSuite exited with non-zero code " + exitCode
                        + " for class " + fullClassName);
            }
        } catch (IOException e) {
            throw new IOException("In EvoSuiteRunner.runEvoSuite: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("EvoSuite run interrupted for " + fullClassName, e);
        }
    }

    /**
     * Assemble the classpath EvoSuite instruments against: the compiled classes of the (fixed)
     * checkout plus every entry the Defects4J {@code cp.test} export provided.
     */
    private String buildProjectClassPath() {
        List<String> entries = new ArrayList<>();
        Path buildPath = config.getProject().getBuildPath();
        if (buildPath != null) {
            entries.add(buildPath.toAbsolutePath().toString());
        }
        if (config.getClassPaths() != null) {
            entries.addAll(config.getClassPaths());
        }
        String sep = System.getProperty("path.separator", ":");
        return String.join(sep, entries);
    }
}
