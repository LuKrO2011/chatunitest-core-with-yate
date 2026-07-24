package zju.cst.aces.measurement;

import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.ValidatorImpl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fault-detection stage: compile the generated suite once, then run each test against both the
 * FIXED and the BUGGY checkout classpaths and decide, per test, whether it detects the bug.
 *
 * <p>A test {@code detected}s the bug iff it PASSES on the fixed program and FAILS on the buggy
 * program (the classic "reveals the defect" criterion). Results are written to
 * {@code <runDir>/bug-detection/bug_detection.csv} with the header pynguin-analyser's
 * {@code bug_check_defects4j} importer expects:</p>
 *
 * <pre>test_name,detected,test_fails,fixed_fails,timed_out</pre>
 *
 * where (per row):
 * <ul>
 *     <li>{@code test_fails}  — the test fails on the BUGGY program (the detection signal),</li>
 *     <li>{@code fixed_fails} — the test fails on the FIXED program (a flaky / over-fitted test),</li>
 *     <li>{@code timed_out}   — execution timed out on either checkout,</li>
 *     <li>{@code detected}    — {@code !timed_out && !fixed_fails && test_fails}.</li>
 * </ul>
 */
public class FaultDetection {

    private final Config config;
    private final Path runDir;
    private final List<String> fixedClasspath;
    private final List<String> buggyClasspath;

    public FaultDetection(Config config, Path runDir, List<String> fixedClasspath, List<String> buggyClasspath) {
        this.config = config;
        this.runDir = runDir;
        this.fixedClasspath = fixedClasspath;
        this.buggyClasspath = buggyClasspath;
    }

    public void run() {
        Path bugDir = runDir.resolve("bug-detection");
        Path csv = bugDir.resolve("bug_detection.csv");
        Path compiledTestsDir = runDir.resolve("faultdetect-build");

        List<String> testFqns;
        try {
            Files.createDirectories(bugDir);
            testFqns = MeasurementSupport.compileTestSuite(config.getTestOutput(), compiledTestsDir, fixedClasspath);
        } catch (Exception e) {
            config.getLogger().warn("FaultDetection: failed to compile suite: " + e);
            return;
        }

        // Two validators differing only in classpath + build folder (the SUT bytecode they load).
        ValidatorImpl fixedValidator =
                new ValidatorImpl(config.getTestOutput(), compiledTestsDir, runDir.resolve("fd-target-fixed"), fixedClasspath);
        ValidatorImpl buggyValidator =
                new ValidatorImpl(config.getTestOutput(), compiledTestsDir, runDir.resolve("fd-target-buggy"), buggyClasspath);

        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("test_name,detected,test_fails,fixed_fails,timed_out");
            writer.newLine();

            for (String testFqn : testFqns) {
                TestExecutionSummary fixedSummary = safeExecute(fixedValidator, testFqn);
                TestExecutionSummary buggySummary = safeExecute(buggyValidator, testFqn);

                boolean timedOut = (fixedSummary == null) || (buggySummary == null);
                boolean fixedFails = fixedSummary != null && fixedSummary.getTestsFailedCount() > 0;
                boolean testFails = buggySummary != null && buggySummary.getTestsFailedCount() > 0;
                boolean detected = !timedOut && !fixedFails && testFails;

                writer.write(String.join(",",
                        testFqn,
                        Boolean.toString(detected),
                        Boolean.toString(testFails),
                        Boolean.toString(fixedFails),
                        Boolean.toString(timedOut)));
                writer.newLine();
            }
        } catch (IOException e) {
            config.getLogger().warn("FaultDetection: failed to write " + csv + ": " + e);
            return;
        }
        config.getLogger().info("FaultDetection: wrote " + csv);
    }

    /**
     * Run a single test class, returning {@code null} for both the 1-minute timeout (handled inside
     * {@link ValidatorImpl#execute}) and any hard execution error (e.g. the class failing to load).
     * A {@code null} summary is recorded as {@code timed_out = true}.
     */
    private TestExecutionSummary safeExecute(ValidatorImpl validator, String testFqn) {
        try {
            return validator.execute(testFqn);
        } catch (Throwable t) {
            config.getLogger().debug("FaultDetection: execution error for " + testFqn + ": " + t);
            return null;
        }
    }
}
