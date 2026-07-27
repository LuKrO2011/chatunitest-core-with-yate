package zju.cst.aces.measurement;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.xml.XMLFormatter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.coverage.CodeCoverageAnalyzer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Uniform measurement stage run after generation completes (post
 * {@code classNameProcessor.processJavaFiles}). Produces, over the whole emitted suite in
 * {@code config.getTestOutput()}, two native measurement artefacts consumed by pynguin-analyser:
 *
 * <ul>
 *     <li>{@code <runDir>/coverage/jacoco.xml} — standard JaCoCo report XML for the target class,
 *         computed by reusing {@link CodeCoverageAnalyzer}'s on-the-fly instrumentation approach
 *         and serialising with {@link org.jacoco.report.xml.XMLFormatter}.</li>
 *     <li>{@code <runDir>/mutation/mutations.xml} — PIT's native mutation report, produced by
 *         shelling out to the PIT command-line ({@code MutationCoverageReport}) scoped to the
 *         target class.</li>
 * </ul>
 *
 * <p>The stage is identical for LLM-generated suites and EvoSuite suites, giving a fair
 * cross-tool comparison.</p>
 */
public class MeasurementStage {

    private final Config config;
    private final Path runDir;
    private final String targetClassFqn;
    private final Path srcRoot;
    private final Path buildPath;
    private final List<String> measurementClasspath;

    /**
     * @param config          headless config for the run
     * @param runDir          the run directory the emitter is populating
     * @param targetClassFqn  fully-qualified name of the modified (target) class
     * @param srcRoot         source root containing the target class (compile source root 0)
     * @param buildPath       compiled-classes directory of the (fixed) checkout
     */
    public MeasurementStage(Config config, Path runDir, String targetClassFqn, Path srcRoot, Path buildPath) {
        this.config = config;
        this.runDir = runDir;
        this.targetClassFqn = targetClassFqn;
        this.srcRoot = srcRoot;
        this.buildPath = buildPath;
        this.measurementClasspath = MeasurementSupport.measurementClasspath(config);
    }

    /** Run both measurement steps; failures in one do not abort the other. */
    public void run() {
        Path compiledTestsDir = runDir.resolve("measure-build");
        List<String> testFqns;
        try {
            testFqns = MeasurementSupport.compileTestSuite(config.getTestOutput(), compiledTestsDir,
                    measurementClasspath);
        } catch (Exception e) {
            config.getLogger().warn("MeasurementStage: failed to compile test suite: " + e);
            testFqns = Collections.emptyList();
        }

        try {
            runJaCoCo(testFqns, compiledTestsDir);
        } catch (Exception e) {
            config.getLogger().warn("MeasurementStage: JaCoCo coverage failed: " + e);
        }

        try {
            runPit(testFqns, compiledTestsDir);
        } catch (Exception e) {
            config.getLogger().warn("MeasurementStage: PIT mutation failed: " + e);
        }
    }

    // ------------------------------------------------------------------ JaCoCo

    private void runJaCoCo(List<String> testFqns, Path compiledTestsDir) throws Exception {
        Path coverageDir = runDir.resolve("coverage");
        Files.createDirectories(coverageDir);
        Path jacocoXml = coverageDir.resolve("jacoco.xml");

        final IRuntime runtime = new LoggerRuntime();
        final Instrumenter instr = new Instrumenter(runtime);

        // Instrument the target class bytecode from the compiled checkout.
        byte[] instrumentedTarget = instrument(instr, targetClassFqn, buildPath);

        final RuntimeData data = new RuntimeData();
        runtime.startup(data);

        // Classpath for loading + executing the suite: measurement deps + SUT classes + compiled tests.
        List<String> runtimeCp = new ArrayList<>(measurementClasspath);
        runtimeCp.add(buildPath.toAbsolutePath().toString());
        runtimeCp.add(compiledTestsDir.toAbsolutePath().toString());
        URL[] urls = MeasurementSupport.toUrls(runtimeCp);

        CodeCoverageAnalyzer.MemoryClassLoader loader =
                new CodeCoverageAnalyzer.MemoryClassLoader(urls, Thread.currentThread().getContextClassLoader());
        loader.addDefinition(targetClassFqn, instrumentedTarget);

        // Run the suite with the instrumented MemoryClassLoader as the THREAD CONTEXT
        // classloader. Otherwise frameworks the LLM tests use (Mockito, JUnit param
        // resolvers, reflection) load the SUT via the context classloader — the
        // uninstrumented copy — so JaCoCo records 0 coverage even though the tests
        // exercise the class (PIT, which runs in its own minion, does see coverage).
        ClassLoader prevCtx = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            for (String testFqn : testFqns) {
                try {
                    Class<?> testClass = loader.loadClass(testFqn);
                    LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                            .selectors(DiscoverySelectors.selectClass(testClass))
                            .build();
                    Launcher launcher = LauncherFactory.create();
                    SummaryGeneratingListener listener = new SummaryGeneratingListener();
                    launcher.registerTestExecutionListeners(listener);
                    launcher.execute(request);
                    org.junit.platform.launcher.listeners.TestExecutionSummary s = listener.getSummary();
                    config.getLogger().info("JaCoCo: " + testFqn + " found=" + s.getTestsFoundCount()
                            + " started=" + s.getTestsStartedCount() + " failed=" + s.getTestsFailedCount());
                    s.getFailures().stream().limit(1).forEach(f ->
                            config.getLogger().warn("  JaCoCo test failure in " + testFqn + ": " + f.getException()));
                } catch (Throwable t) {
                    config.getLogger().warn("JaCoCo: skipping test " + testFqn + " (" + t + ")");
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(prevCtx);
        }

        final ExecutionDataStore executionData = new ExecutionDataStore();
        final SessionInfoStore sessionInfos = new SessionInfoStore();
        data.collect(executionData, sessionInfos, false);
        runtime.shutdown();

        // Analyse the ORIGINAL (uninstrumented) target class to build the coverage bundle, then
        // serialise standard JaCoCo report XML.
        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);
        File targetClassFile = classFile(targetClassFqn, buildPath);
        try (InputStream in = new FileInputStream(targetClassFile)) {
            analyzer.analyzeClass(in, targetClassFqn);
        }
        IBundleCoverage bundle = coverageBuilder.getBundle("chatunitest-d4j");

        XMLFormatter xmlFormatter = new XMLFormatter();
        try (OutputStream out = new FileOutputStream(jacocoXml.toFile())) {
            IReportVisitor visitor = xmlFormatter.createVisitor(out);
            visitor.visitInfo(sessionInfos.getInfos(), executionData.getContents());
            visitor.visitBundle(bundle, new DirectorySourceFileLocator(srcRoot.toFile(), "utf-8", 4));
            visitor.visitEnd();
        }
        config.getLogger().info("MeasurementStage: wrote " + jacocoXml);
    }

    private static byte[] instrument(Instrumenter instr, String fqn, Path buildPath) throws IOException {
        File compiledFile = classFile(fqn, buildPath);
        try (InputStream in = new FileInputStream(compiledFile)) {
            return instr.instrument(in, fqn);
        }
    }

    private static File classFile(String fqn, Path buildPath) {
        return new File(buildPath.toFile(), fqn.replace('.', '/') + ".class");
    }

    // --------------------------------------------------------------------- PIT

    private void runPit(List<String> testFqns, Path compiledTestsDir) throws Exception {
        Path mutationDir = runDir.resolve("mutation");
        Files.createDirectories(mutationDir);

        String pitClasspath = MeasurementSupport.pitLauncherClasspath();
        if (pitClasspath == null) {
            config.getLogger().warn("MeasurementStage: PIT launcher classpath unavailable "
                    + "(set PIT_CLASSPATH or add pitest CLI deps to the harness pom); skipping mutation.");
            return;
        }

        // Never fall back to a package glob when the generated suite compiled to nothing:
        // cp.test carries the Defects4J DEVELOPER tests for the same package, so a glob makes
        // PIT silently measure THOSE instead of the generated suite — reporting the project's
        // own mutation score as if it were the tool's. If there are no generated test FQNs,
        // there is nothing of ours to measure; write an explicit empty report and stop.
        if (testFqns.isEmpty()) {
            config.getLogger().warn("MeasurementStage: no generated test classes compiled for "
                    + targetClassFqn + " — skipping PIT (not falling back to the developer tests).");
            Files.write(mutationDir.resolve("mutations.xml"),
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations partial=\"true\"></mutations>\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }

        // Application classpath PIT instruments/runs against: SUT + tests + measurement deps.
        List<String> appCp = new ArrayList<>(measurementClasspath);
        appCp.add(buildPath.toAbsolutePath().toString());
        appCp.add(compiledTestsDir.toAbsolutePath().toString());

        String targetTests = String.join(",", testFqns);

        List<String> command = new ArrayList<>();
        // Launch PIT with the SAME JVM the harness runs on (java.home) — JDK 11, the same runtime as
        // Defects4J + EvoSuite. currentJavaBinary() resolves java.home so the minion JVMs PIT spawns
        // inherit JDK 11 and can execute the EvoSuite-generated JUnit4 suite.
        command.add(currentJavaBinary());
        command.add("-cp");
        command.add(pitClasspath);
        command.add("org.pitest.mutationtest.commandline.MutationCoverageReport");
        command.add("--reportDir");
        command.add(mutationDir.toAbsolutePath().toString());
        command.add("--targetClasses");
        command.add(targetClassFqn);
        command.add("--targetTests");
        command.add(targetTests);
        command.add("--sourceDirs");
        command.add(srcRoot.toAbsolutePath().toString());
        // PIT's --classPath is COMMA-separated (NOT the platform path separator). Joining with ':'
        // makes PIT treat the whole classpath as one bogus element, so it finds no SUT/test classes
        // and reports "0 mutation test units in pre scan".
        command.add("--classPath");
        command.add(String.join(",", appCp));
        // Scope mutation generation to the SUT's own compiled-classes dir. Without this PIT scans the
        // whole --classPath (which includes the harness fat jar, itself carrying stale copies of
        // common libraries pulled in transitively) and can mutate the wrong copy of the target class.
        command.add("--mutableCodePaths");
        command.add(buildPath.toAbsolutePath().toString());
        command.add("--outputFormats");
        command.add("XML");
        command.add("--timestampedReports");
        command.add("false");
        command.add("--testPlugin");
        command.add("junit5");
        // Tolerate a non-green suite: EvoSuite occasionally emits an environment-sensitive test that
        // fails on the unmutated code. Without this PIT aborts the whole class ("Mutation testing
        // requires a green suite"); with it PIT drops the failing test and mutates against the rest.
        command.add("--skipFailingTests");
        command.add("true");

        config.getLogger().info(String.format("[%s] Running PIT for class < %s >",
                config.pluginSign, targetClassFqn));
        config.getLogger().debug("PIT command: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(config.getProject().getBasedir());
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            config.getLogger().warn("PIT exited with code " + exitCode + " for " + targetClassFqn);
        }

        // With --timestampedReports false PIT writes <reportDir>/mutations.xml directly. Normalise
        // to <runDir>/mutation/mutations.xml if PIT nested it under a timestamped folder anyway.
        Path expected = mutationDir.resolve("mutations.xml");
        if (!Files.exists(expected)) {
            try (Stream<Path> walk = Files.walk(mutationDir)) {
                Path found = walk.filter(p -> p.getFileName().toString().equals("mutations.xml"))
                        .findFirst().orElse(null);
                if (found != null && !found.equals(expected)) {
                    Files.copy(found, expected);
                }
            }
        }
        if (Files.exists(expected)) {
            config.getLogger().info("MeasurementStage: wrote " + expected);
        }
    }

    private static String packageGlob(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "*" : fqn.substring(0, dot) + ".*";
    }

    /** Absolute path to the {@code java} binary of the JVM currently running the harness. */
    private static String currentJavaBinary() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            File candidate = new File(javaHome, "bin/java");
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        return "java";
    }
}
