package zju.cst.aces.driver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Thin {@link ProcessBuilder} wrapper around the {@code defects4j} command-line tool, following the
 * shell-out pattern used by {@link zju.cst.aces.runner.solution_runner.MUTAPRunner}. Assumes
 * {@code defects4j} is on the {@code PATH} of the executing node.
 */
public final class Defects4jCli {

    private Defects4jCli() {
    }

    /** {@code defects4j checkout -p <project> -v <version> -w <workDir>}. */
    public static void checkout(String project, String version, File workDir) throws IOException, InterruptedException {
        workDir.getParentFile().mkdirs();
        run(List.of("defects4j", "checkout", "-p", project, "-v", version, "-w", workDir.getAbsolutePath()), null);
    }

    /** {@code defects4j compile} executed inside {@code workDir}. */
    public static void compile(File workDir) throws IOException, InterruptedException {
        run(List.of("defects4j", "compile"), workDir);
    }

    /**
     * {@code defects4j export -p <property>} executed inside {@code workDir}; returns the property
     * value printed to stdout (trimmed). Throws when defects4j exits non-zero.
     */
    public static String export(String property, File workDir) throws IOException, InterruptedException {
        ProcResult result = run(List.of("defects4j", "export", "-p", property), workDir);
        return result.stdout.trim();
    }

    private static ProcResult run(List<String> command, File workDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir);
        }
        // defects4j export prints the value to stdout and progress/logging to stderr, so keep them
        // separate (do NOT redirectErrorStream) to avoid polluting the returned value.
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = pump(process.getInputStream(), stdout);
        Thread errReader = pump(process.getErrorStream(), stderr);
        outReader.start();
        errReader.start();

        int exit = process.waitFor();
        outReader.join();
        errReader.join();

        if (exit != 0) {
            throw new IOException("Command " + command + " failed (exit " + exit + "):\n" + stderr);
        }
        return new ProcResult(stdout.toString(), stderr.toString(), exit);
    }

    private static Thread pump(java.io.InputStream in, StringBuilder sink) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (!first) {
                        sink.append('\n');
                    }
                    sink.append(line);
                    first = false;
                }
            } catch (IOException ignored) {
                // Stream closed; nothing to do.
            }
        });
    }

    /** Captured result of a defects4j invocation. */
    private static final class ProcResult {
        final String stdout;
        final String stderr;
        final int exit;

        ProcResult(String stdout, String stderr, int exit) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exit = exit;
        }
    }
}
