# BUILD_NOTES — Part A (Defects4J harness) compile & verification checklist

This branch (`d4j-harness`) implements **Part A** of `HARNESS_PLAN.md`.

> Iron rule: do **not** claim anything compiles until `mvn clean install` is green on a real node.

## Compile status (updated)

`mvn -DskipTests clean package` is now **green under JDK 17** and produces
`target/chatunitest-core-with-yate-1.0-SNAPSHOT-jar-with-dependencies.jar`
(Main-Class `zju.cst.aces.driver.Defects4jDriver`), with PIT, JaCoCo-report,
EvoSuite-runtime and Mockito classes bundled (verified). Fixes made to get there:

- **`EvoSuiteRunner` now `extends MethodRunner`** (was `AbstractRunner`).
  `AbstractRunner.start()` is package-private, so a `solution_runner` sub-package
  class cannot legally `@Override` it — every sibling runner already extends
  `MethodRunner` (public `start()`). Constructor delegates `super(config,
  fullClassName, methodInfo)`; `start()` is still replaced wholesale.
- **`evosuite-standalone-runtime` pinned to `1.0.6`** (§5.5): `1.2.0` is not on
  Maven Central (only ≤1.0.6). It only needs to *resolve* for the build.
- **`com.mkonst:yate-java:1.0-SNAPSHOT`** is a pre-existing dependency absent from
  Maven Central: clone `github.com/michaelkonstantinou/yate-java` and `mvn install`
  it first (the Docker `harness-build` stage does this automatically).
- **`maven-assembly-plugin`** added to produce the fat jar (§5.7).
- **PIT launched with the harness's own `java.home`** (not PATH's `java`) in
  `MeasurementStage.runPit`, so PIT runs on the same JDK 17 as the Java-17 fat jar
  even when PATH's `java` is the JDK 11 the Defects4J/EvoSuite toolchain needs.
- **`Defects4jDriver --target-class <fqn>`** added: restricts a run to a single
  modified class, so the pynguin-analyser `defects4j-java-loop` wrapper maps one
  SLURM array index → one `(project, bug, class)` run dir.

Still **runtime-pending** (need a Defects4J + JDK 11/17 + LLM node — see §5): the
EvoSuite CLI flags (§5.3), PIT target scoping (§5.4/§5.12), `PromptTemplate`
tolerating a null `promptPath` for EvoSuite (§5.8), Yate init from
`.env.chatunitest` (§5.13), and the `bug_detection.csv` column semantics (§5.10).

---

## 1. Files added / changed

### New Java sources
| File | Role (plan section) |
|---|---|
| `api/impl/Defects4jProject.java` | A1 — headless `Project` over a Defects4J checkout |
| `driver/Defects4jDriver.java` | A2 — `main()` orchestration (checkout/compile/export → generate → measure → emit) |
| `driver/Defects4jCli.java` | A2 — `ProcessBuilder` wrapper for the `defects4j` CLI |
| `runner/solution_runner/EvoSuiteRunner.java` | A3 — EvoSuite sibling runner (shells out to the EvoSuite jar) |
| `measurement/MeasurementStage.java` | A4 — JaCoCo `jacoco.xml` + PIT `mutations.xml` |
| `measurement/MeasurementSupport.java` | A4 — shared classpath assembly + suite compilation helper |
| `measurement/FaultDetection.java` | A5 — buggy-vs-fixed fault detection → `bug_detection.csv` |
| `emit/RunDirEmitter.java` | A6 — writes the pynguin-analyser run directory |

### Modified Java sources
| File | Change |
|---|---|
| `api/config/YateConfig.java` | A7 — `configPath` now resolved from `CHATUNITEST_YATE_ENV` / `chatunitest.yate.env` / cwd `.env.chatunitest` (was a hard-coded absolute path) |
| `parser/ProjectParser.java` (ctor ~:52) | A1 — `srcFolderPath` derived from `project.getCompileSourceRoots()` instead of `basedir/src/main/java` |
| `api/config/Config.java` (ConfigBuilder) | A1 — added `validatorTargetPath` field + `validatorTargetPath(Path)` builder method + `resolveValidatorTarget()`; the 3 hard-coded `basedir/target` validator constructions now use it |
| `runner/ClassRunner.java` (`selectRunner`) | A3 — added `case "EVOSUITE"` routing to `EvoSuiteRunner` |
| `api/impl/RunnerImpl.java` (`selectRunner`) | A3 — added `case "EVOSUITE"` routing to `EvoSuiteRunner` |
| `pom.xml` | A7 — added measurement/runtime deps (see §3) |

### New non-Java files
| File | Role |
|---|---|
| `.env.chatunitest` | A7 — Yate env template (replaces the hard-coded absolute path) |
| `BUILD_NOTES.md` | this file |

`ProjectImpl` and the Maven plugin path are **untouched** — all changes are additive or
default-preserving (see §4).

---

## 2. Compile / verify checklist (run on a real node)

1. `mvn -q clean install` — must be green. Watch for the items in §5 first.
2. Confirm the new deps resolve (§3), especially `org.evosuite:evosuite-standalone-runtime` and the
   `org.pitest` CLI artifacts (versions may need bumping to what's in your repo/mirror).
3. Smoke-test the driver headlessly (needs `defects4j` on `PATH`, JDK 8 for the D4J checkout toolchain
   per plan risk #5):
   ```bash
   export LLM_BASE_URL=... LLM_API_KEY=... LLM_MODEL=gpt-4o-mini
   export CHATUNITEST_YATE_ENV=$PWD/.env.chatunitest
   java -cp target/chatunitest-core-with-yate-1.0-SNAPSHOT-jar-with-dependencies.jar \
     zju.cst.aces.driver.Defects4jDriver \
     --projects Lang --bugs 1 --tool plain --model gpt-4o-mini --seed 42 --out runs/
   ```
   (You will need a shade/assembly jar — the harness reuses its own `java.class.path` for the
   measurement + PIT classpaths; see §5.7.)
4. EvoSuite path: `export EVOSUITE_JAR=/path/evosuite-1.2.0.jar` then `--tool evosuite` (no LLM env
   needed).
5. Verify a run dir contains: `d4j-info.json`, `statistics.csv`, `evosuite-tests/**`, `sources/**`,
   `coverage/jacoco.xml`, `mutation/mutations.xml`, `bug-detection/bug_detection.csv`, `logs/`.
6. Ingest with pynguin-analyser Part B importers (out of scope here).

---

## 3. pom.xml dependencies added (A7)

Added after the existing `org.jacoco.core`:

- `org.jacoco:org.jacoco.report:0.8.8` — **required** so `MeasurementStage` can serialise standard
  JaCoCo XML (`XMLFormatter`, `IReportVisitor`, `DirectorySourceFileLocator`). The original pom only
  had `org.jacoco.core`.
- `org.mockito:mockito-core:3.8.0`, `org.mockito:mockito-junit-jupiter:3.8.0` — generated LLM tests
  use Mockito; needed on the compile/measurement classpath.
- `org.evosuite:evosuite-standalone-runtime:1.2.0` — to compile/run EvoSuite's JUnit 4 output.
- `org.pitest:pitest-command-line:1.15.2`, `org.pitest:pitest-entry:1.15.2`,
  `org.pitest:pitest-junit5-plugin:1.2.1` — PIT CLI + JUnit 5 support, so the harness's own
  `java.class.path` can serve as the PIT launcher classpath when `PIT_CLASSPATH` is unset.

**Verify the versions** against your mirror. In particular `evosuite-standalone-runtime` and
`pitest-junit5-plugin` versions are best-guesses (see §5.5, §5.6). A `jar-with-dependencies`
(assembly/shade) build is recommended so §5.7 holds.

---

## 4. How the Maven path stays intact

- `ProjectParser` ctor: `ProjectImpl.getCompileSourceRoots()` returns `MavenProject`'s compile source
  roots (still `basedir/src/main/java` for a standard project), so behaviour is unchanged. The old
  `basedir/src/main/java` remains as a fallback when compile roots are empty.
- `Config` validator target: `resolveValidatorTarget()` returns the **legacy `basedir/target`** when
  `validatorTargetPath` is null (the Maven plugin never sets it), so the Maven default is preserved.
- `selectRunner`: only a new `case "EVOSUITE"` was added; all existing cases + `default` are unchanged.
- Temperature `0.1` is set **by the driver** (`.temperature(0.1)`), not by changing the shared
  ConfigBuilder default (which stays `0.5`), so the Maven plugin's default is untouched.

---

## 5. Assumptions to verify at compile/run time (smallest-reasonable-assumption register)

### 5.1 `defects4j export` property names (driver)
Assumed properties: `cp.test`, `dir.src.classes`, `dir.bin.classes`, `classes.modified` (standard).
`revision.id.buggy` / `revision.id.fixed` are pulled via `defects4j export` too but may **not** be
valid export properties on all D4J versions — `safeExport(...)` swallows failures and stores `""`.
If your D4J emits commit ids only via `defects4j info`, add that path. `dir.src.classes` /
`dir.bin.classes` are assumed **relative to the checkout** and resolved against `basedir`
(`Defects4jProject.resolveAgainstBasedir` also tolerates absolute values).

### 5.2 `classes.modified` format
Assumed newline/comma/whitespace-separated FQNs; split on `[,\s]+`.

### 5.3 EvoSuite CLI (`EvoSuiteRunner`)
Assumed invocation: `java -jar $EVOSUITE_JAR -class <fqn> -projectCP <cp> -Dtest_dir=<out>
-Dsearch_budget=<n> -Djunit_check=false -Duse_separate_classloader=false`. Flag names/behaviour
(especially `test_dir`, scaffolding suppression) must be confirmed against your EvoSuite version.
`EVOSUITE_JAR` env var is required. `EVOSUITE_SEARCH_BUDGET` optional (default 60s).

### 5.4 PIT CLI (`MeasurementStage.runPit`)
Assumed main class `org.pitest.mutationtest.commandline.MutationCoverageReport` and flags
`--reportDir --targetClasses --targetTests --sourceDirs --classPath --outputFormats XML
--timestampedReports false --testPlugin junit5`. With `--timestampedReports false` PIT is assumed to
write `<reportDir>/mutations.xml` directly; a fallback walk copies a nested `mutations.xml` if not.
`--testPlugin junit5` requires `pitest-junit5-plugin` on the launcher classpath. If programmatic PIT
is preferred later, swap the ProcessBuilder for `EntryPoint`/`MutationCoverage` — CLI was chosen per
the plan's "prefer CLI if programmatic is uncertain".

### 5.5 `evosuite-standalone-runtime` coordinates/version
`org.evosuite:evosuite-standalone-runtime:1.2.0` assumed available. Adjust groupId/version to match
the EvoSuite jar you run (§5.3).

### 5.6 PIT artifact versions
`pitest-command-line` / `pitest-entry` pinned to `1.15.2` (matches the existing `pitest-maven`),
`pitest-junit5-plugin` to `1.2.1` (a best-guess compatible version). Verify compatibility.

### 5.7 Measurement classpath = harness `java.class.path`
`MeasurementSupport.measurementClasspath` and `pitLauncherClasspath` reuse the running harness's
`java.class.path` to supply JUnit 5 / vintage / Mockito / JaCoCo / PIT. This **requires launching the
driver from a fat jar or an explicit `-cp` that contains those deps** (a plain `mvn exec` reactor
classpath also works). If you launch differently, set `PIT_CLASSPATH` explicitly and ensure the
compile classpath in `compileTestSuite` includes JUnit/Mockito/EvoSuite runtime.

### 5.8 `EvoSuiteRunner extends AbstractRunner` eagerly builds a `PromptGenerator`
`AbstractRunner`'s constructor does `new PromptGenerator(config)` → `new PromptTemplate(config,
properties, config.getPromptPath(), ...)`. For EvoSuite runs `promptPath` is null. **Verify
`PromptTemplate` tolerates a null `promptPath` at construction.** If it throws, either set
`config.promptPath` in the driver for EvoSuite, or refactor `EvoSuiteRunner` to not go through the
LLM-oriented super-constructor. (EvoSuite still reaches `EvoSuiteRunner` only after
`PhaseImpl.createPhase("EVOSUITE")` falls back to the default `PhaseImpl`, and generation is guarded
to run EvoSuite **once per class** via a static set.)

### 5.9 `Model.fromString` is strict
`--model` / `LLM_MODEL` must be one of the `Model` enum names (e.g. `gpt-4o-mini`, `gpt-4o`,
`deepseek-chat`, `meta-llama/Llama-3.3-70B-Instruct`). Unknown names throw. Add an enum entry for any
new endpoint model.

### 5.10 `bug_detection.csv` column semantics
Header is written **exactly** as `test_name,detected,test_fails,fixed_fails,timed_out`. Semantics
chosen (verify against `bug_check_defects4j.parse_bug_detection_csv` in pynguin-analyser):
`test_fails` = fails on **buggy**; `fixed_fails` = fails on **fixed**; `timed_out` = null summary from
either run (1-min `ValidatorImpl` timeout **or** hard execution error); `detected =
!timed_out && !fixed_fails && test_fails`. If the importer expects different column meanings, adjust
`FaultDetection.run()`.

### 5.11 `TestCompiler` static fields
`TestCompiler` keeps `buildFolder` / `testOutputFolder` / `targetTestsFolder` as **static** fields, so
the two `ValidatorImpl`s in `FaultDetection` share them. Safe here because both are constructed with
the **same** compiled-tests dir and only `classpathElements` (instance) differs; runs are sequential
per target. Do not parallelise fault detection across targets in one JVM without revisiting this.

### 5.12 JaCoCo report scope
`MeasurementStage.runJaCoCo` instruments + analyses **only the target class** (the modified class),
producing a target-scoped `jacoco.xml`. If a modified class has multiple top-level targets or the
importer expects the whole bundle, extend to instrument/analyse all `classes.modified`.

### 5.13 `config.print()` initialises Yate
The driver calls `config.print()` for LLM tools (it triggers `ConfigYate.initialize(configPath)` +
`PromptService.initialize()`). This needs a readable `.env.chatunitest` (see `CHATUNITEST_YATE_ENV`
/ `--yate-env`). Failure is caught and logged, not fatal, but Yate repair won't run without it.

### 5.14 `testOutput` nesting
`ConfigBuilder.testOutput(p)` appends the project `artifactId` (e.g. `lang-1`) under `p`. The emitter
and measurement both read `config.getTestOutput()`, so they stay consistent; just note the generated
tests live under `runDir/generated-tests/<artifactId>/…`, not directly under `generated-tests/`.

---

## 6. Known limitations left for later parts / milestones

- `bug_diff` in `d4j-info.json` is emitted as `""` (driver does not compute the diff yet).
- `llm_query_count` in `statistics.csv` is emitted as `0` (no counter is threaded through the LLM
  phases yet; RQ2 cost axis needs this wired from the generation path).
- Only `plain` (default path + Yate) and `evosuite` are exercised end-to-end by intent; `coverup` /
  `hits` / `symprompt` map to their `PhaseType` enum values and should work as `phaseType` switches
  (plan milestone 6) but are unverified. There is **no** `TESTSPARK` enum value — `testspark` would
  currently map to a non-existent `TESTSPARK` phase and fall back to the default runner; add a phase
  before using it.
