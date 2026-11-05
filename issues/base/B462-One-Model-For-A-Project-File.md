# B462 — One Model For A Project File

**Status:** Backlog, first in line.
**Size:** XL - six parts, sized below.

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

- **Refusing every unknown key** means a file written for a later Porelo stops an earlier one. That matters for a project's repository: once one
  member's newer Porelo writes a new key, every member must update before their next start. The refusal says the key is unknown to this version and
  that a later one may know it. Whether a file should name the least version it needs is the operator's.
- **The source comments of a draft** (where a value came from, what a plugin said) leave the text the generator builds. They go to the start's output,
  as `notes` already do ([B455](README.md#b455)). Whether SnakeYAML can write them as comments from the model is not shown.
- **C4, editing that keeps comments, is not there today through SnakeYAML:** see below. Keeping it would take another way, or the operator lets it go.
- **The window's schema** (`ProjectFileSchema`) is then read from the model, not from `ProjectSchema`'s lists.

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

Sizes are a reading of the code, not a count of it:

- **The file models, their binding and their check, M-L:**
  - `ProjectFile`, `RepositoryEntry`, `RepositoryFile` and the shared types;
  - `ProjectSchema` folded into them;
  - the round-trip tests and the native test.
- **Writing, M:** SnakeYAML from a model; `ProjectProposal` and `DefaultProject` on it.
- **`RepositoryFile` in use, S-M:** for `porelo.yml` and `.porelo.yml`, with `ProjectFile` in `organization/git-tree` reading and writing through the
  models.
- **The resolved task, and `Project`'s users moved onto it, L-XL:** the largest and least known part. It is sized again once the resolved object's methods
  are set.
- **Edits, `EgressEdit`, S-M:** as C4 is decided.
- **The docs and the window's schema, S-M.**
