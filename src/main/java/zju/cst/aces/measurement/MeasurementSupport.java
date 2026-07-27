package zju.cst.aces.measurement;

import zju.cst.aces.api.config.Config;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared helpers for the measurement / fault-detection stages: assembling the (superset)
 * measurement classpath, compiling an emitted test suite once, and locating the PIT launcher.
 */
public final class MeasurementSupport {

    private MeasurementSupport() {
    }

    /**
     * The superset classpath needed to compile and run BOTH LLM (JUnit 5 + Mockito) and EvoSuite
     * (JUnit 4 vintage + evosuite-standalone-runtime) suites uniformly. Composed of:
     * <ul>
     *     <li>the Defects4J {@code cp.test} entries carried on {@link Config#getClassPaths()},</li>
     *     <li>the compiled-classes directory of the checkout ({@link Config} project build path),</li>
     *     <li>the harness's own runtime classpath ({@code java.class.path}), which supplies JUnit 5,
     *         the vintage engine, JaCoCo, and (once added to the pom, see BUILD_NOTES) Mockito and
     *         the EvoSuite runtime.</li>
     * </ul>
     */
    public static List<String> measurementClasspath(Config config) {
        Set<String> cp = new LinkedHashSet<>();
        if (config.getClassPaths() != null) {
            cp.addAll(config.getClassPaths());
        }
        if (config.getProject() != null && config.getProject().getBuildPath() != null) {
            cp.add(config.getProject().getBuildPath().toAbsolutePath().toString());
        }
        // The real EvoSuite jar (a jar FILE) must be on the classpath so EvoSuite's EvoRunner /
        // AgentLoader can locate and self-attach its instrumenting agent when running an
        // EvoSuite-generated JUnit4 suite. The evosuite-runtime CLASSES merged into the harness fat
        // jar are NOT enough (AgentLoader scans for an evosuite *jar file*, and a version match with
        // the generator matters). Resolve it from EVOSUITE_JAR (the same jar used for generation) and
        // place it BEFORE the harness fat jar so its classes win. Harmless for LLM (JUnit5) suites.
        String evosuiteJar = System.getenv("EVOSUITE_JAR");
        if (evosuiteJar != null && !evosuiteJar.isBlank()) {
            cp.add(new File(evosuiteJar).getAbsolutePath());
        }
        String harnessCp = System.getProperty("java.class.path");
        if (harnessCp != null) {
            String sep = System.getProperty("path.separator", ":");
            cp.addAll(Arrays.asList(harnessCp.split(java.util.regex.Pattern.quote(sep))));
        }
        return new ArrayList<>(cp);
    }

    /** Convert classpath string entries into URLs for a {@link java.net.URLClassLoader}. */
    public static URL[] toUrls(List<String> classpath) {
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath) {
            try {
                urls.add(new File(entry).toURI().toURL());
            } catch (Exception ignored) {
                // Skip malformed entries.
            }
        }
        return urls.toArray(new URL[0]);
    }

    /**
     * Compile every {@code *.java} under {@code testSrcDir} into {@code outDir}, returning the
     * fully-qualified names of the compiled top-level test classes (derived from the path relative
     * to {@code testSrcDir}, which {@code ClassNameProcessor} keeps aligned with the package).
     */
    public static List<String> compileTestSuite(Path testSrcDir, Path outDir, List<String> classpath)
            throws IOException {
        List<String> fqns = new ArrayList<>();
        if (testSrcDir == null || !Files.exists(testSrcDir)) {
            return fqns;
        }
        Files.createDirectories(outDir);

        List<File> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(testSrcDir)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    // Skip chatunitest's aggregator suite (<Class>_Suite.java). It uses the JUnit
                    // Platform Suite API (org.junit.platform.runner / suite.api) which is not on the
                    // measurement classpath, so it fails to compile — and because javac compiles the
                    // whole batch in one task, ITS errors drop every sibling test class too (0 .class
                    // emitted → 0 coverage, PIT skipped). The suite is only a runner convenience;
                    // JaCoCo and PIT are driven by the individual *Test FQNs, so excluding it lets the
                    // real test classes compile and be measured.
                    .filter(p -> !p.getFileName().toString().endsWith("_Suite.java"))
                    .forEach(p -> {
                        sources.add(p.toFile());
                        String rel = testSrcDir.relativize(p).toString();
                        rel = rel.substring(0, rel.length() - ".java".length());
                        fqns.add(rel.replace(File.separatorChar, '.'));
                    });
        }
        if (sources.isEmpty()) {
            return fqns;
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            String sep = System.getProperty("path.separator", ":");
            List<String> options = Arrays.asList(
                    "-classpath", String.join(sep, classpath),
                    "-d", outDir.toAbsolutePath().toString());
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(sources);
            // Best-effort: some generated tests may not compile; we still return the FQNs of those
            // whose .class landed in outDir (callers tolerate missing classes at load time).
            boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            if (!ok) {
                int shown = 0;
                for (javax.tools.Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                        System.out.println("MEASURE-COMPILE-DIAG >>> "
                                + (d.getSource() == null ? "?" : new File(d.getSource().getName()).getName())
                                + ":" + d.getLineNumber() + " " + d.getMessage(null));
                        if (++shown >= 6) {
                            break;
                        }
                    }
                }
            }
        }

        // Derive the returned FQNs from the COMPILED .class files, not the source paths.
        // chatunitest writes LLM test sources FLAT in testSrcDir while they declare a
        // package, so a source-path FQN ("FooTest") doesn't match the compiled layout
        // ("pkg/FooTest.class"); the old path-based filter dropped all of them, leaving
        // JaCoCo (which needs explicit test FQNs) with nothing to run — 0 coverage —
        // while PIT still found them via its package-glob fallback. Scanning outDir gives
        // the package-correct FQNs and skips inner classes.
        List<String> compiledFqns = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(outDir)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .forEach(p -> {
                        String rel = outDir.relativize(p).toString();
                        rel = rel.substring(0, rel.length() - ".class".length());
                        compiledFqns.add(rel.replace(File.separatorChar, '.'));
                    });
        }
        return compiledFqns;
    }

    /**
     * Classpath for launching the PIT command line in a child JVM. Prefers the {@code PIT_CLASSPATH}
     * environment variable; otherwise falls back to the harness's own {@code java.class.path} (which
     * works once the pitest CLI dependencies are on the harness pom — see BUILD_NOTES). Returns
     * {@code null} when no plausible classpath is available.
     */
    public static String pitLauncherClasspath() {
        String fromEnv = System.getenv("PIT_CLASSPATH");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String harnessCp = System.getProperty("java.class.path");
        if (harnessCp != null && harnessCp.contains("pitest")) {
            return harnessCp;
        }
        return null;
    }
}
