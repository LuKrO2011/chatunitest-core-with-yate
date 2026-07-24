package zju.cst.aces.api.impl;

import zju.cst.aces.api.Project;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Headless {@link Project} implementation backed by a Defects4J checkout instead of a Maven
 * reactor. It carries exactly the four coordinates ChatUniTest needs to parse and validate a
 * project without touching {@link org.apache.maven.project.MavenProject}:
 *
 * <ul>
 *     <li>{@code basedir} — the Defects4J checkout directory (fixed or buggy),</li>
 *     <li>{@code compileSourceRoots} — the absolute source directory, derived from the Defects4J
 *         {@code dir.src.classes} export,</li>
 *     <li>{@code buildPath} — the compiled-classes directory, from {@code dir.bin.classes},</li>
 *     <li>{@code classPaths} — the runtime/test classpath entries, from splitting the Defects4J
 *         {@code cp.test} export on the platform path separator.</li>
 * </ul>
 *
 * <p>All Maven-only accessors ({@code getParent}, {@code getGroupId}, {@code getArtifactId},
 * {@code getPackaging}, {@code getArtifactPath}) return sensible headless stubs. In particular
 * {@code getPackaging()} returns {@code "jar"} so that {@link zju.cst.aces.api.Task}'s
 * pom-packaging short-circuit is never taken.</p>
 */
public class Defects4jProject implements Project {

    private final File basedir;
    private final List<String> compileSourceRoots;
    private final Path buildPath;
    private final List<String> classPaths;
    private final String groupId;
    private final String artifactId;

    /**
     * @param basedir            the checkout directory produced by {@code defects4j checkout -w <dir>}
     * @param srcClassesRelative the {@code dir.src.classes} export (relative to {@code basedir}, e.g. {@code "src/main/java"})
     * @param binClassesRelative the {@code dir.bin.classes} export (relative to {@code basedir}, e.g. {@code "target/classes"})
     * @param cpTest             the {@code cp.test} export (path-separator-delimited classpath string)
     * @param projectId          the Defects4J project id (e.g. {@code "Lang"}), used only for the stub group/artifact ids
     * @param bugId              the Defects4J bug id (e.g. {@code "1"}), used only for the stub artifact id
     */
    public Defects4jProject(File basedir, String srcClassesRelative, String binClassesRelative,
                            String cpTest, String projectId, String bugId) {
        this.basedir = basedir;
        this.compileSourceRoots = new ArrayList<>();
        this.compileSourceRoots.add(resolveAgainstBasedir(basedir, srcClassesRelative).toString());
        this.buildPath = resolveAgainstBasedir(basedir, binClassesRelative);
        this.classPaths = splitClassPath(cpTest);
        this.groupId = "org.defects4j." + (projectId == null ? "unknown" : projectId.toLowerCase());
        this.artifactId = (projectId == null ? "unknown" : projectId.toLowerCase())
                + "-" + (bugId == null ? "0" : bugId);
    }

    private static Path resolveAgainstBasedir(File basedir, String maybeRelative) {
        if (maybeRelative == null || maybeRelative.isBlank()) {
            return basedir.toPath();
        }
        Path p = new File(maybeRelative).toPath();
        if (p.isAbsolute()) {
            return p;
        }
        return basedir.toPath().resolve(maybeRelative);
    }

    /**
     * Split a Defects4J {@code cp.*} export into individual classpath entries. Defects4J emits a
     * ':'-delimited string on Unix; we split on the JVM path separator to stay portable.
     */
    public static List<String> splitClassPath(String cp) {
        if (cp == null || cp.isBlank()) {
            return new ArrayList<>();
        }
        String sep = System.getProperty("path.separator", ":");
        return Arrays.stream(cp.split(java.util.regex.Pattern.quote(sep)))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public Project getParent() {
        // Flat Defects4J checkout: no reactor parent.
        return null;
    }

    @Override
    public File getBasedir() {
        return basedir;
    }

    @Override
    public String getPackaging() {
        // Must not be "pom" so Task never short-circuits the pom-packaging guard.
        return "jar";
    }

    @Override
    public String getGroupId() {
        return groupId;
    }

    @Override
    public String getArtifactId() {
        return artifactId;
    }

    @Override
    public List<String> getCompileSourceRoots() {
        return compileSourceRoots;
    }

    @Override
    public Path getArtifactPath() {
        // No packaged jar in a Defects4J checkout; the compiled-classes dir is the closest analogue
        // and is only consulted by Maven-specific paths that this project never exercises.
        return buildPath;
    }

    @Override
    public Path getBuildPath() {
        return buildPath;
    }

    @Override
    public List<String> getClassPaths() {
        return classPaths;
    }
}
