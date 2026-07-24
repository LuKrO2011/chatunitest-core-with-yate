<!--
This file is part of Pynguin Analyser.

SPDX-FileCopyrightText: 2026 Pynguin Analyser Contributors

SPDX-License-Identifier: EUPL-1.2
-->
# Plan: A Unified Java Test-Generation + Measurement Harness on Defects4J

> Supersedes the earlier `LLM_D4J.md` (deleted). Complements — does not replace —
> the committed `DEFECTS4J.md` / `EVOSUITE.md` design docs.

## Goal

Fork **`michaelkonstantinou/chatunitest-core-with-yate`** (the implementation behind
Konstantinou et al., arXiv:2601.09695) into a single Java harness that, for each
Defects4J bug, runs **every Java test-generation tool** — the LLM strategies
(`plain`, `coverup`, `hits`, `symprompt`, `testspark`, all with Yate repair) **and
EvoSuite** — measures all of them **uniformly with JaCoCo + PIT**, adds **fault
detection** (buggy vs fixed), and writes run directories that **pynguin-analyser ingests
→ SQLite → `rq_tables` + the qualitative-coding markdown**, exactly as the current
evosuite+defects4j flow does.

Defects4J is used as the **source + bug dataset** (checkout / compile / classpath /
buggy-fixed pairs). Its *measurement* harness (Cobertura/Major) is **not** used — JaCoCo
+ PIT replace it for all tools, matching the paper's metrics.

### Settled (from requirements)

| Decision | Choice |
|---|---|
| Where the engine lives | **Fork `chatunitest-core-with-yate`** (single Java harness for all tools) |
| Coverage / mutation engine | **JaCoCo + PIT**, uniform across LLM tools *and* EvoSuite |
| Fault detection | **Yes** — run each suite against buggy + fixed checkouts |
| Results / RQ tables | **pynguin-analyser** SQLite → `rq_tables` + qualitative-coding `.qdpx` |
| Hard requirement | End product is the qualitative-coding markdown, as with evosuite+defects4j today |

### Decisions I am making (flag if you disagree)

1. **Measurement interchange format = native JaCoCo XML + PIT `mutations.xml`**, and we
   write two new importers in pynguin-analyser (templated on the existing
   `execute_defects4j.py` / `mutate_defects4j.py`, and already sketched in `EVOSUITE.md`).
   *Alternative rejected:* emitting Cobertura/Major-shaped files to reuse importers —
   lossy (PIT operator names, JaCoCo branch detail) and hacky.
2. **The Defects4J orchestration (checkout/compile/export) lives in the fork** as a new
   headless Java driver, so the fork is the complete "harness for all Java tools." It
   shells out to the `defects4j` CLI (`ProcessBuilder`, like `MUTAPRunner`). *Alternative:*
   keep orchestration in pynguin-analyser's `defects4j_run.py` and call the fork jar per
   class — reuses existing Python but splits the harness. I recommend Java-in-fork.

---

## Architecture

```
FORK: chatunitest-core-with-yate  (headless, no Maven reactor)
  Defects4jDriver (new Java main)
    for each (project, bug, modified-class):
      defects4j checkout -v Xf -w fixed ; -v Xb -w buggy ; defects4j compile (both)
      defects4j export -p cp.test,cp.compile,dir.src.classes,dir.bin.classes,
                          classes.modified,tests.trigger,revision.id.{buggy,fixed}
      Config <- Defects4jProject(fixed checkout, explicit classpath)     [seam b]
      GENERATE with tool = config.phaseType | "evosuite"                  [seam a]
        LLM tools: existing PhaseImpl/solution + Yate repair (unchanged)
        evosuite : new EvoSuiteRunner (ProcessBuilder over EvoSuite jar)
      MEASURE (uniform, over config.testOutput):                          [seam c]
        JaCoCo  -> coverage/jacoco.xml    (reuse CodeCoverageAnalyzer)
        PIT     -> mutation/mutations.xml  (programmatic or CLI)
      FAULT DETECT: run suite vs fixed & buggy classpaths                 [seam d]
        -> bug-detection/bug_detection.csv
      EMIT run dir  (d4j-info.json, statistics.csv, tests/, sources/, logs/)
         │
         ▼
PYNGUIN-ANALYSER (Python, import-only)
  analyse-loop  (discovers run dirs by d4j-info.json)
    execute_java (NEW: JaCoCo xml -> module_results)
    mutate_java  (NEW: PIT mutations.xml -> cr_results/cr_mutants)
    metrics_java (reuse: reads test_content from DB)
    bug_check_defects4j (reuse: bug_detection.csv -> bug_check_results)
  merge -> summarise -> export(.qdpx)  ==> qualitative-coding markdown
  rq_tables / stats                    ==> paper tables
```

Seam labels (a)–(d) map to the extension points found in the fork recon (§ Part A).

---

## Part A — Fork changes (`chatunitest-core-with-yate`)

The Maven coupling is narrow and localized; making it headless is the enabling step.

### A1. Headless project shim — `Defects4jProject implements api.Project`
- `api/Project.java` is an interface; the only impl is `ProjectImpl(MavenProject)`
  (`api/impl/ProjectImpl.java:20`). Add `api/impl/Defects4jProject.java` returning:
  `getBasedir()` = checkout dir, `getCompileSourceRoots()` = `dir.src.classes`,
  `getBuildPath()` = `dir.bin.classes`, `getClassPaths()` = `cp.test` split, plus
  stub `getPackaging/GroupId/ArtifactId/Parent`.
- Generalize the two Maven hardcodes so parsing/validation don't assume a Maven layout:
  - `parser/ProjectParser.java:52` hardcodes `basedir/src/main/java` → derive from
    `project.getCompileSourceRoots()`.
  - `Config.java:211` validator `target` = `basedir/target` → make it a Config field
    (point at a temp build dir).
- Build the Config headless: `new Config.ConfigBuilder(new Defects4jProject(...))`
  `.classPaths(d4jCp).url(LLM_BASE_URL).apiKeys(...).model(...).phaseType(tool)...build()`
  (`Config.java:171`, `:320`, `:395`, `:446`, `:283`).

### A2. Defects4J driver — `Defects4jDriver` (new `main`)
- New class with a `main()` (the fork currently has no generation `main`). Args:
  `--projects Lang,Cli --bugs 1-10 --tool plain --model gpt-4o-mini --out <dir>`
  (mirror pynguin-analyser's `defects4j_loop` CLI surface).
- Per `(project, bug)`: `ProcessBuilder("defects4j", "checkout"/"compile"/"export", …)`
  (pattern from `runner/solution_runner/MUTAPRunner.java:63`). Enumerate targets from
  `classes.modified`.
- Calls `Task.startClassTask(fqn)` (`api/Task.java:213`) for the target class, then the
  measurement + fault-detection stages, then the emitter (A6).

### A3. EvoSuite as a sibling tool — `EvoSuiteRunner`
- EvoSuite is absent today (0 refs). Add `runner/solution_runner/EvoSuiteRunner extends
  AbstractRunner` that shells out to the EvoSuite jar targeting the modified class with
  the fixed checkout classpath, writing JUnit into `config.getTestOutput()`.
- Wire it in `ClassRunner.selectRunner` (`runner/ClassRunner.java:102`) and
  `RunnerImpl.selectRunner` (`api/impl/RunnerImpl.java:37`) under `phaseType == "evosuite"`
  (bypasses the LLM PhaseImpl path entirely — EvoSuite is not prompt-based).
- EvoSuite output is **JUnit 4** + needs `evosuite-standalone-runtime` on the measurement
  classpath (see A7 cross-cutting).

### A4. Uniform measurement stage — JaCoCo + PIT over `config.testOutput`
- **JaCoCo**: `coverage/CodeCoverageAnalyzer.java` already uses `org.jacoco.core`
  (`analyzeCoverage`, `:78`; per-method instruction/branch/line + uncovered ranges) but
  is only reachable via its own `main` (`:291`). Add a stage class (sibling of
  `phase/step/Validation.java`) that runs it over the whole emitted suite and serializes
  standard **JaCoCo report XML** to `coverage/jacoco.xml`.
- **PIT**: today only a `mvn pitest:mutationCoverage` shell-out in `MUTAPRunner.java:63`
  (`pitest-maven 1.15.2` in pom). Run PIT over the compiled suite (programmatic
  `org.pitest` entry or CLI `ProcessBuilder`) restricted to the target class; write
  `mutation/mutations.xml` (PIT's native XML: `<mutation detected status …><mutator>…`).
- Both stages hook **after generation completes**, at the clean seam post
  `classNameProcessor.processJavaFiles(config.getTestOutput())` (`Task.java:237`).
- Same stage runs identically for LLM tools and EvoSuite → fair comparison.

### A5. Fault-detection stage — `FaultDetection` (buggy vs fixed)
- Reuse `api/impl/ValidatorImpl.execute(fullTestName)` / `util/TestCompiler.executeTest`
  (`TestCompiler.java:72`), which runs a compiled test against an arbitrary
  `classpathElements + buildFolder` via a `URLClassLoader`.
- Compile the generated suite once; run each test twice — classpath/bin pointing at
  **fixed** vs **buggy** checkout. `detected` = passes on fixed AND fails on buggy.
- Emit `bug-detection/bug_detection.csv` with the analyser's exact header:
  `test_name,detected,test_fails,fixed_fails,timed_out` (consumed by
  `bug_check_defects4j.parse_bug_detection_csv`).

### A6. Run-directory emitter (the ingestion contract)
Write the manifest pynguin-analyser's Defects4J importers require:
- `d4j-info.json` — `{tool_name, project, bug_id, target_module (FQN), config_id, seed,
  buggy_commit, fixed_commit, sut_file, run_id, bug_diff}` (primary detection marker).
  `tool_name` ∈ {`plain-d4j`,`coverup-d4j`,`hits-d4j`,`symprompt-d4j`,`testspark-d4j`,
  `evosuite-d4j`}; `config_id` carries strategy+granularity+model (e.g.
  `plain-class-gpt4omini`).
- `statistics.csv` — single row `RunId,TargetModule,ConfigurationId,ProjectName,RandomSeed`
  (+ any extra columns, absorbed via `ALTER TABLE` on ingest). Add LLM-query count here
  for the paper's cost/RQ2 axis.
- `evosuite-tests/**/*.java` — the generated test sources (dir name kept for detection;
  holds LLM *or* EvoSuite tests). Source of `test_content` + `metrics_java`.
- `sources/<pkg>/<Class>.java` — the SUT source (for `sut_content` in the markdown).
- `coverage/jacoco.xml`, `mutation/mutations.xml`, `bug-detection/bug_detection.csv`,
  `logs/*.log`.

### A7. Cross-cutting fork fixes
- **Yate config**: `YateConfig.java:12` hardcodes `/Users/michael.konstantinou/…
  .env.chatunitest`. Make `configPath` a real config/env input; ship a template env.
- **LLM endpoint**: set `Config.url` to our `LLM_BASE_URL` (OpenAI-compatible; the
  `Model` enum URLs are overridable via `.url(...)`). Pin `temperature = 0.1` (paper's
  Plain-LLM) — the fork defaults to 0.5.
- **Measurement classpath**: the runner must assemble a superset — JUnit 5 + Mockito
  (LLM tests), JUnit 4 **vintage** engine (EvoSuite tests), `evosuite-standalone-runtime`,
  plus `pitest-junit5-plugin` so PIT runs both. `TestCompiler` already uses the JUnit
  Platform Launcher, which runs JUnit 4 via vintage.

---

## Part B — pynguin-analyser changes (import-only)

Schema is unchanged (`module_results`, `cr_results`/`cr_mutants`, `bug_check_results`,
`test_metrics`, `statistics`, `summaries`). Only parsers + registration change.

### B1. New importers (JaCoCo + PIT) — the only real gap
- `pipeline/execute_java.py` (NEW): parse **JaCoCo `jacoco.xml`** → `module_results`
  (covered/executable lines, branches, uncovered branches). Template:
  `pipeline/execute_defects4j.py` (`_find_classes`/`covered_lines`/`branch_stats` →
  `execute._write_sqlite`). Parser shape already sketched in `EVOSUITE.md:264-269`.
- `pipeline/mutate_java.py` (NEW): parse **PIT `mutations.xml`**
  (`KILLED/SURVIVED/NO_COVERAGE/TIMED_OUT`, `<mutator>`) → `cr_results`/`cr_mutants`.
  Template: `pipeline/mutate_defects4j.py`; sketch in `EVOSUITE.md:310-312`. Map
  PIT status → the `cosmic_ray_*` columns; keep PIT mutator names in
  `cr_mutants.operator`.
- Document the mutation-score definition (PIT `killed/(covered)` vs Cosmic Ray) in
  `rq_tables` for cross-tool fairness (same caveat noted in `DEFECTS4J.md`).

### B2. Provider + dispatch
- `providers/defects4j.py`: parametrize `tool_name` — read it from `d4j-info.json`
  (already written there) instead of the hardcoded constant `evosuite-d4j`
  (`utils/defects4j_utils.py:53`).
- `providers/__init__.py`: register `plain-d4j` / `coverup-d4j` / `hits-d4j` /
  `symprompt-d4j` / `testspark-d4j` / `evosuite-d4j` → `Defects4JProvider(tool_name=…)`.
- `analyse.py`: broaden the import-only guard `provider.tool_name == "evosuite-d4j"`
  (`analyse.py:413`) to `endswith("-d4j")`; route coverage/mutation to the **new** JaCoCo/PIT
  importers for `-d4j` runs (or select by a `coverage_kind` field in `d4j-info.json`).
  Add the names to `_TOOL_CHOICES` (`analyse.py:59`).
- `analyse-loop`: discovery already keys on `d4j-info.json`, so it works for every `-d4j`
  tool once `--tool` is threaded through.

### B3. Unchanged
`metrics_java` (reads `test_content` from DB), `merge`, `summarise` (use `--group-by-run`
for Defects4J), `export` (`.qdpx`), qualitative-coding, `rq_tables`, `stats`,
`bug_check_defects4j`. The qualitative-coding markdown (`summarise` → `summary_text` →
`export`) needs only the DB fields the importers already fill.

---

## Risks / open questions

1. **Headless ChatUniTest** *(central)* — running generation+validation without a Maven
   reactor via a custom `Project` + explicit classpath. De-risk first (Milestone 1).
2. **Measurement classpath assembly** — mixing JUnit 4 (EvoSuite) + JUnit 5 + Mockito +
   EvoSuite runtime + PIT junit5 plugin on one classpath for uniform JaCoCo/PIT. Concrete
   and fiddly; test with one EvoSuite suite + one LLM suite early.
3. **PIT integration** — programmatic vs CLI; scoping to the target class; JUnit 5 support
   via `pitest-junit5-plugin`. Falls back to CLI `ProcessBuilder` if needed.
4. **Compile-repair yield** — the paper drops ~20% non-compiling LLM tests even after
   repair; Yate repair raises yield. Keep it enabled; report compile/pass rate.
5. **Defects4J JDK** — Defects4J baseline is Java 8; the fork/JaCoCo/PIT toolchain and
   EvoSuite must run under a compatible JVM. Confirm on the target node.
6. **Model/temp parity + cost** — pin model + temperature per experiment; capture LLM
   query count into `statistics` for RQ2.

---

## Milestones (build order)

1. **Headless spike (fork)**: `Defects4jProject` + generalize the two hardcodes; run
   `plain` generation against one compiled Defects4J checkout with an explicit classpath.
   Proves Part A1 (the enabling risk).
2. **Uniform measurement (fork)**: wire `CodeCoverageAnalyzer` → `jacoco.xml` and PIT →
   `mutations.xml` over the emitted suite; verify on the Milestone-1 output.
3. **Analyser importers (Python)**: `execute_java.py` + `mutate_java.py`; ingest the
   Milestone-2 run dir end-to-end → `module_results`/`cr_results`; confirm `summarise` +
   `export` produce the qualitative-coding markdown.
4. **Fault detection (fork)**: `FaultDetection` stage → `bug_detection.csv` →
   `bug_check_results`; the "Bug Detected" markdown field populates.
5. **EvoSuite tool (fork)**: `EvoSuiteRunner` measured by the *same* stage; first
   head-to-head EvoSuite vs `plain` on one bug.
6. **Remaining strategies (config only)**: `coverup`, `hits`, `symprompt`, `testspark`
   (existing phases/prompts) — just `phaseType` switches.
7. **Batch + cluster**: `Defects4jDriver` `--projects/--bugs/--tool/--model` sweep; SLURM
   array wiring mirrors the EvoSuite path; then `merge`/`summarise`/`rq_tables` over all.
