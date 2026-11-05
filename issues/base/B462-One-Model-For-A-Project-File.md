# B462 — One Model For A Project File

**Status:** Implementing.
**Size:** about 1000 minutes, part by part below; no function changes. No question is open.

**What must be true.** A project file and a repository's file are each read and written through one typed model and nothing else. The operator,
2026-11-05: a project file read and written outside one model is a serious fault. It goes ahead of other work.

1. **Two file models, 1:1 to their YAML,** read and written by SnakeYAML and checked in one place. What a model cannot hold does not exist.
   **Each level of a file is one fixed Java type:** a section's YAML maps onto its type, and nothing reads raw maps beside it.
2. **Reading:** SnakeYAML onto that model, held to fixed types, never a class the file names. A key Porelo does not know is refused, with line, key
   and where it belongs.
3. **Writing:** only from a valid model, by SnakeYAML. No text built by hand: `ProjectProposal`'s `StringBuilder` goes.
4. **Proof:**
   - A model written, read and written again gives the same text.
   - Every draft the generator makes passes the reader, and so does every example in `doc/project-file.md`.
   - The operator's file, with `image` at two places, is a test that must be refused.
5. **The running task is never written:** one resolved object built from the files, below.
6. **Porelo never changes an existing project file,** by the operator's word. It writes only new files: a first start's draft and `project init`.
   Where it changes a file today, it shows the finished entry instead and the person writes it; Porelo then reads their change like any other file.
   That is a host allowed from a running task (`EgressControl`, `EgressEdit`, the daemon's `SetEgress`) and `porelo project repository
   add/change/remove` (`ProjectRepositoryCommand`, and `ProjectFile`'s `add`, `propose`, `changeRow` and `remove` in `organization/git-tree`).
   `EgressEdit` goes, and with it the constraint C4: with no edits, there are no comments to keep.
7. **An unknown key is refused,** saying this Porelo does not know it and may be older than the file. A file names no least version, by the
   operator's word.

**No backward compatibility,** by the operator's word: a file that does not fit the model is refused with what is wrong, and thrown away. No
migration, no release reading old keys, no `migrate` command. The refusal says where each key belongs, so a person can write the file anew. New
key names are therefore no step of their own.

## How It Is Checked

The operator, 2026-11-05: the structure is Java classes; there is one way to check that an instance holds correct data and its required fields are
filled; a filled instance serializes to its YAML, and that YAML deserializes back to an equal instance, 1:1, held by unit tests.

- **Records, one checking class of ours in plain Java, no Bean Validation.** Nothing needs reflection metadata, so nothing can fall silent in the native
  image. That is how the measurement below fell out.
- **The binding is written per type** from SnakeYAML's node tree, which gives each fault its line.
- **Unit tests:**
  - the round trip of every type: a filled instance to YAML and back to an equal one;
  - the same for every example in `doc/project-file.md`;
  - every draft the generator makes, read back.
- **One native-image test:** an invalid file is refused by the shipped binary, as the JVM refuses it.

## The Target Structure

The operator's, 2026-11-05. Today one record serves both a file and the running task; that ends.

**`ProjectFile`**, for `project.yml`:
- `ProjectInfo project`: name, description, securityClass, upstream, nickname, review;
- `Image image`;
- `BuildSources build`: `package_sources`;
- `Egress egress`, `Limits limits`;
- `Mail`, `Builds`, `Parameters`, `BranchScheme branch`, credentials;
- `Map<String, RepositoryEntry> repositories`.

**`RepositoryEntry`:** upstream, central, description, issues, agent, nickname, review, maturity, `BranchScheme`. **No image, egress or limits.** Egress
is the project's alone, so a repository that needs other rules is another project. This settles [B461](README.md#b461)'s format, and [B461](README.md#b461) is closed into this.

**`RepositoryFile`**, for `porelo.yml` and the local `.porelo.yml`: `Image image` and nothing else.

**Shared types, one each:** `Image`, `BranchScheme`, `Egress` and `Limits`. `Limits.Declared` goes.
- **`Image`:**
  - `from` and `env`, and `run` or `runFile`, never both.
  - `run` allows only `RUN`, `ARG` and `ENV`, and refuses `FROM`, `COPY`, `ADD`, `USER` and `ENTRYPOINT`, each with its line.

**The resolved task, never written,** is built from `ProjectFile`, `RepositoryEntry` and `RepositoryFile`.
- **One image source, taken whole,** with no key-by-key merge, and named at the start. The order is the local `.porelo.yml`, then `porelo.yml`, then
  the project's.
- **`egressFor` and `ImageChoice.merge`** move into it or go.

## What Stands Today

- **`ProjectReader`, 1149 lines,** loads plain maps with `SafeConstructor` and maps them by hand. Its rules are in `ProjectReader`, in `Project`
  (1110 lines), in `ProjectSchema` (757 lines, the key lists) and in `Repository`, `Mail`, `Limits`, `Egress` and `Parameters`.
- **`Project` is used in 87 main files and 37 test files,** by a search on its name. Most are in `apps/app-task` (19), `apps/app-messaging` (15),
  `apps/app-model` (12), `apps/app-gate` (10) and `apps/app-project` (8).
- **`ProjectReader` is called from 25 main files and 42 test files.** `RepositoryImageReader` and `ProjectFile` in `organization/git-tree` each load YAML
  of their own. `ProjectFile` writes a proposal with `new Yaml().dump(...)` of a map.
- **Text built by hand:** `ProjectProposal`'s draft, `DefaultProject`, and `EgressEdit`. `EgressEdit` replaces the `egress` block in the text and leaves
  every other byte, its comments included. That is the patch-only editing the operator calls C4.
- **A generated `project.yml` writes `base_image` twice,** at the top and under the repository, both fixed in `ProjectProposal`: the operator's
  finding from their test, first filed as [B461](README.md#b461). Worse, the entry under the repository beats that repository's own `porelo.yml`, by today's order.
- **An unknown key elsewhere is accepted with a warning,** decided on 2026-09-30 (`doc/project-file.md`, "A key Porelo does not know"), so that a file
  for a later Porelo runs on an earlier one.

## Moving `Project`'s Users

Most of `Project`'s users read a setting of the running task, so they move to the resolved task, not to a file model. Only the reading, the writing
and the edits touch the files. **How many need more than a changed type is not read yet,** so this part is sized once the resolved object's methods
are set, as they decide how much each user changes.

## What Changes, And What It Costs

- **Refusing every unknown key** means a file written for a later Porelo stops an earlier one. Once one member's newer Porelo has a new key in a
  project's file, every member must update before their next start.
- **The source comments of a draft** (where a value came from, what a plugin said) leave the text the generator builds. They go to the start's output,
  as `notes` already do ([B455](README.md#b455)).
- **The window's schema** (`ProjectFileSchema`) is then read from the model, not from `ProjectSchema`'s lists. Where the window today changes a file, it
  shows the finished entry, which Agent Frontend takes on.

## Files Put Right By Hand

No migration code. Once the reader stands, an agent writes these anew, by hand, in the new format:

- **`project.yml`:** `porelo-project/project.yml` and `porelo-test-project/project.yml` in our repositories; the operator's own, two
  today in their configuration folder. The operator says which of theirs to keep.
- **`porelo.yml`, now `RepositoryFile` with `from` and `run`:** in `porelo`, `porelo-adapters`, `porelo-agy`, `porelo-ai.github.io`, `porelo-buildtools`,
  `porelo-claude-code`, `porelo-codex`, `porelo-frontend`, `porelo-host`, `porelo-intellij`, `porelo-message-matrix`, `porelo-message-sluice`,
  `porelo-omp`, `porelo-parent` and `porelo-pi`, each by its owner.
- **The examples** in `doc/project-file.md` and the other docs, as part of the docs.

Test files that hold a project file change with the code that reads them.

## The Measurement So Far

**Stopped on the operator's word.** They want a measurement like this in a small dummy project with the planned classes, about five minutes, not a
slice of the rebuild in `porelo`. What ran, four native builds of `porelo` in a worktree, is kept until they choose:

| | size | `--version`, median | read, median | read, peak memory | invalid file, native |
|---|---|---|---|---|---|
| `main` | 49.09 MB | 30.2 ms | 2.3 ms | 18.1 MB | refused |
| records, own checks | 49.15 MB | 30.5 ms | 2.2 ms | 17.7 MB | refused, with its line |
| Hibernate Validator | 57.28 MB | 29.6 ms | 5.2 ms | 31.9 MB | **accepted** |
| the same, with traced metadata | 57.28 MB | 30.3 ms | 7.4 ms | 34.8 MB | refused |

- **Without the tracing agent's metadata, Bean Validation accepted an invalid file in the native image, silently,** which the JVM refused. With it, 41
  reflection and 19 resource entries, 6 of them for our own records, so each new record needs a traced entry kept in step.
- **The `fuin` BOM pins Hibernate Validator 8.0.3** (Validation 3.0). Its messages follow the machine's language unless a locale is fixed, and it
  logs a line at its start.
- **SnakeYAML 2.7's node tree with comments fails on the docs' own example:** a `ClassCastException` in its `Composer` on the `image` section's
  comments. Where it runs, it moves the spaces before a trailing comment and changes blank lines.

## Parts

In minutes, from the work itself. New code is mostly today's rules moved, not new rules.

| Part | Kind | What | Minutes |
|---|---|---|---|
| File models | new | About 15 records: `ProjectFile`, `ProjectInfo`, `RepositoryEntry`, `RepositoryFile`, `Image`, `BuildSources`, `Egress`, `Limits`, `Mail` and its two inner types, `Builds`, `Parameters` with `Parameter`, `BranchScheme`, credentials | 40 |
| Binding | new, ported | One binder per type from the node tree, with the line of a fault; `ProjectReader`'s 1149 lines are its source | 90 |
| One check | new, ported | About 130 refusals today in `ProjectReader` (57), `Project` (15), `RepositoryImageReader` (10), `Limits`, `Mail`, `Parameter`, `Parameters`, `Egress`, `Repository`, `BranchScheme`, `Builds`; plus `run` taking only `RUN`, `ARG` and `ENV` | 80 |
| Writer | new | SnakeYAML from a model; `ProjectProposal` and `DefaultProject` build a model, not text | 60 |
| Tests | new | The round trip of each type; each docs example; each generated draft; one native test refusing an invalid file | 90 |
| Resolved task | new | Built from the three files: one image source taken whole, `egressFor`, `ImageChoice.merge` in it | 60 |
| `Project`'s users | mechanical | 87 main files, 374 mentions of the type: a renamed type. 762 call sites, about 450 of them `name()`, `upstream()` and `securityClass()`, which keep their names. About 60 move, the image's and egress's, to the resolved object | 65 |
| Their tests | mechanical | 37 files, the same | 40 |
| No edits | deleted, replaced | `EgressEdit` and its two tests go, 1 file and 332 test lines; `EgressControl`, `SetEgress`, `ProjectRepositoryCommand` and `ProjectFile`'s four writes show the finished entry instead | 75 |
| Deleted | deleted | `ProjectSchema`'s lists, read from the model; `Limits.Declared`; the hand-built text | 30 |
| Docs and schema | text | `doc/project-file.md` and others; the window's schema from the model | 45 |
| Files by hand | text | The project files and 15 `porelo.yml` above | 30 |
| Run and reading | check | Unit tests of the reactor, the native test, one reading and its fixes | 90 |
| **Total** | | | **about 1000** |

**More than replacing:** the binding and the check port today's rules into one place; that is where the time is, about 170 minutes. The users of
`Project` are a rename and some 60 moved calls.
