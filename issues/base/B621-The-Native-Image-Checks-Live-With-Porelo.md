# B621 — The Native-Image Checks Live With Porelo

```json
{
  "$schema": "issue-1.json",
  "id": "B621",
  "title": "The Native-Image Checks Live With Porelo",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 30,
  "blockedBy": [],
  "covers": "porelo-ffm-check and porelo-cpu-check are built, tested and published from this repository; porelo-buildtools no longer holds them, and porelo-planner takes porelo-cpu-check from here."
}
```

**Why this size.** M: two modules of about 380 lines of code and 270 of tests moved as they are, a new `build/`
aggregator, the checks' plugin dependency moved from the root's plugin management into the eleven module profiles that
turn a check on, the publish set and its rule, a decision, `doc/build.md` and a changelog entry; of the code, only
ffm-check's JSON import changes.

Filed 2026-11-11 as this repository's part of `porelo-project` ST320, "The work tools live with their users".

**What must be true.** The two checks that guard porelo's native images, `ForeignRegistrations` (every FFM downcall
registered for native-image) and `CpuFloor` (no instruction above the CPU floor), are porelo's own: changed, tested and
released with the code they guard. Today they live in porelo-buildtools for history's sake, and a change to either
waits for a build-tools release.

How it is today:

- The root pom runs `ffm-check` at `prepare-package` and `cpu-check` at `package`; core, host, shield and vault run
  `ffm-check` too. Both come from porelo-buildtools as `porelo-ffm-check` and `porelo-cpu-check`.
- `porelo-ffm-check` depends on `porelo-json`, which porelo-buildtools' release and machines modules use as well, and
  porelo's `dist/package-check`.
- porelo-planner runs `porelo-cpu-check` on its own native image.

## Plan

Read in today's code (porelo's root `pom.xml`, the module poms, `porelo-buildtools`' `ffm-check` and `cpu-check`,
porelo-planner's `planner/app/pom.xml`). `build/` does not exist yet; it is new, beside `builds/`, which is the build
ecosystems' plugin API and not the build's own tooling.

1. **The modules.** `build/pom.xml` (`porelo-build-checks`, packaging `pom`, parent the root) with `build/ffm-check` and
   `build/cpu-check`: artifact ids `porelo-ffm-check` and `porelo-cpu-check`, packages `org.fuin.porelo.ffmcheck` and
   `org.fuin.porelo.cpucheck`, classes `ForeignRegistrations` (249 lines) and `CpuFloor` (130), their tests
   `ForeignRegistrationsTest` (7 tests) and `CpuFloorTest` (8) and READMEs copied unchanged, with the source and
   javadoc jars as today. Their parent is porelo's root, so they are on `porelo.version` and released with porelo. The
   root names `build` as a module.
2. **JSON.** ffm-check reads and writes through porelo-wire's `org.fuin.porelo.wire.Json` (a reactor dependency at
   `${project.version}`) in place of porelo-json: it uses only `Json.parse` and `Json.write`, both of which read and
   write alike in the two classes, maps, lists, strings, booleans and doubles; the one difference is that wire's
   `Json.write` also escapes Unicode format and line or paragraph separator characters, which no class or method name
   in a `reachability-metadata.json` holds. `dist/package-check` keeps porelo-json, which stays a build-tools library
   for porelo-release and porelo-machines.
3. **Where the plugin dependency sits.** Today the root's `pluginManagement` gives `exec-maven-plugin` both checks
   as dependencies. Maven merges managed plugin dependencies into every declaration of that plugin, the root's own
   `-N` tooling executions (`machines`, `release`, `check-*`) included; at `porelo.version` that would make every
   `-N exec:exec@...` on a fresh clone, or after the version is raised, resolve a snapshot of the checks not yet built.
   Today the version is `${porelo.buildtools.version}`, a property porelo-parent sets to the build tools' line; it
   becomes `${porelo.version}`, porelo's own line, never `${project.version}`, which in `agents/stub` is an agent's
   version. So the `<dependencies>` with the two checks leave the root's plugin management and go, at
   `${porelo.version}`,
   into the `exec-maven-plugin` declaration of each profile that turns a check on: the `ffm-check` profile in `core`,
   `host`, `shield` and `vault` (`porelo-ffm-check`), and the `native` profile in `apps/app`, `daemon`, `hooks`,
   `host`, `builds/stub`, `agents/stub` and `ecosystems/stub` (`porelo-cpu-check`). The executions stay managed in the
   root. The plugin dependency is a reactor edge, so `-pl core -am` builds the check first; the module order is not
   set by hand. Leaving the root also keeps it free of the edge root, ffm-check, wire, root.
4. **Published.** Both modules set `maven.deploy.skip` and `skipPublishing` to `false`, as `wire` does, so they go to
   Central with porelo's snapshots and releases. `AGENTS.md`'s "Six artifacts reach Central" rule and the root pom's
   publishing comment name eight. `porelo-bom` does not list them: they are a build's tools, not an API.
5. **Documents.** `doc/build.md` (the checks live in `build/`, not porelo-buildtools); `doc/decisions.md`'s "The build
   tooling lives in its own repository" entry and its index row: the FFM and CPU checks now live with porelo, the
   package check's porelo-json and the release and machines tools stay in porelo-buildtools. A changelog entry under
   `changelog/unreleased`, *Changed*: the two checks are built and published from porelo.

Red first: none, the tests move as they are. What proves the move: the moved tests pass in porelo's reactor; the deploy
dry run that `check-deploy` reads shows `injected-central-publishing` in both modules; `-Pnative,ffm-check
-Dagent=true -pl core -am verify` builds `porelo-ffm-check` first and refuses a removed registration in core's
`reachability-metadata.json` (put back afterwards); and `-N exec:exec@release -Drelease.args="check-commits ..."` runs
from a local repository holding no porelo-ffm-check at porelo's version.

## After the plan reading

agy (gemini) read the plan; the build takes its answers:

- `build/README.md`, linking both checks, and a link to it in the root `README.md`, or `check-readmes` fails at
  `validate`.
- `build/ffm-check` and `build/cpu-check` take `porelo-build-checks` as parent with `relativePath` `../pom.xml`, as every
  grouped module here does; `build/pom.xml` takes the root.
- Both modules bind `cyclonedx-maven-plugin` and set `Automatic-Module-Name`, as every published jar here does.
- ffm-check names `porelo-wire` without a version, from the root's dependency management at `${porelo.version}`.
- Also changed: `AGENTS.md`'s other two sentences on six artifacts, `agents/README.md`, and `build.yml`'s comment on
  where `porelo-ffm-check` comes from.
- porelo-planner can take `porelo-cpu-check` from porelo's snapshot at once, but its own release waits until a porelo
  release carries the checks, since `check-releases` refuses a snapshot version.

## Acceptance

- `./mvnw -Pnative install` runs both checks from this repository's modules, and their tests pass here.
- The groupId `org.fuin.porelo` and the artifact ids stay, but porelo's and the build tools' version lines differ:
  every pom that names `porelo-ffm-check` or `porelo-cpu-check`, here and in porelo-planner, names porelo's version
  explicitly (`${porelo.version}` here), and none takes `porelo.buildtools.version`, so none resolves a build-tools
  version by mistake; a grep of the poms shows it. The root's `-N` tooling runs without either check resolved.
- ffm-check names no porelo-json; it reads through porelo-wire's `Json`.
- Both checks are in the deploy dry run's publishing set, and `AGENTS.md` names eight artifacts on Central.
- porelo-planner's build takes `porelo-cpu-check` from porelo's published artifacts.

## Aligned with BT72

`porelo-buildtools` BT72 is the other half. The order, with no time in which neither repository publishes the checks:

1. This issue lands; porelo's next snapshot on `main` holds both checks at porelo's version, and its next release
   carries them to Central.
2. porelo-planner points `planner/app/pom.xml`'s `porelo-cpu-check` at porelo's coordinate with porelo's version
   named explicitly (a porelo release, or porelo's snapshot until one exists), in place of
   `${porelo.buildtools.version}`, which after the drop would resolve a build-tools version that holds no check. Its
   own change.
3. Only once porelo's first snapshot holds both artifacts and porelo-planner takes `porelo-cpu-check` from porelo,
   porelo-buildtools drops `ffm-check/` and `cpu-check/` (`porelo-buildtools` BT72). Its earlier releases keep both
   on Central, so a build pinned to them never breaks.

Both sides agree: ffm-check reads JSON through porelo-wire's `Json` in porelo, and porelo-json stays in
porelo-buildtools for its release and machines tools and porelo's `dist/package-check`.

## Estimate

M, est=63 (p80 229) by the median velocity. There are no open questions.

## To be checked

- Maven 3.9's reactor takes the plugin dependency in a profile as an edge, so `-pl core -am` and the whole reactor
  build `build/ffm-check` before core: seen in the first `validate` with the profiles on.
- porelo-planner resolves porelo's snapshots and releases from the repositories it already reads (it reads
  porelo-buildtools' today); to be confirmed with the planner's owner when handing over.
