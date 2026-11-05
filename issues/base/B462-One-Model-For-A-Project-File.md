# B462 — One Model For A Project File

**Status:** Backlog, first in line: the measurement below comes before any code.
**Size:** XL - split after the measurement; the measurement itself is S.

**What must be true.** A project file is read and written through one typed model and nothing else. The operator, 2026-11-05: a project file read and
written outside one model is a serious fault. It goes ahead of other work.

1. **One typed model** is the only definition of a project file. What it cannot hold does not exist. **Each level of the file is one fixed
   Java type,** such as `Project`, `Repository`, `Egress` and `Image`: a section's YAML maps onto its type, and nothing reads raw maps beside it.
   Whether `Repository` holds an `Egress` and an `Image` is the operator's, with [B461](README.md#b461).
2. **Reading:** YAML through SnakeYAML onto that model, held to fixed types, never a class the file names, then checked in one place. A key Porelo
   does not know is refused, with line, key and where it belongs.
3. **Writing:** only from a valid model, by SnakeYAML. No text built by hand: `ProjectProposal`'s `StringBuilder` goes.
4. **Proof:**
   - A model written, read and written again gives the same text.
   - Every draft the generator makes passes the reader, and so does every example in `doc/project-file.md`.
   - The operator's file, with `image` at two places, is a test that must be refused.
5. **`porelo.yml`** (`RepositoryImageReader`) and **`ProjectFile`** in `organization/git-tree` follow the same rule.

**No backward compatibility,** by the operator's word: a file that does not fit the model is refused with what is wrong, and thrown away. No
migration, no release reading old keys, no `migrate` command. The refusal says where each key belongs, so a person can write the file anew.

The format, one place for `image` and `egress` or none at a repository, is the operator's ([B461](README.md#b461)); this follows it.

## What Stands Today

- **`ProjectReader`, 1149 lines,** loads plain maps with `SafeConstructor` and maps them by hand. Its rules are in `ProjectReader`, in `Project`
  (1110 lines), in `ProjectSchema` (757 lines, the key lists) and in `Repository`, `Mail`, `Limits`, `Egress` and `Parameters`.
- **25 main files call it, and 42 test files.**
- **`RepositoryImageReader` has its own load, and so does `ProjectFile`.** `ProjectFile` writes a proposal with `new Yaml().dump(...)` of a map.
- **Text built by hand:** `ProjectProposal`'s draft, `DefaultProject`, and `EgressEdit`. `EgressEdit` replaces the `egress` block in the text and
  leaves every other byte, its comments included. That is the patch-only editing the operator calls C4.
- **An unknown key elsewhere is accepted with a warning,** decided on 2026-09-30 (`doc/project-file.md`, "A key Porelo does not know"), so that a
  file for a later Porelo runs on an earlier one.

## What Changes, And What It Costs

- **Refusing every unknown key** means a file written for a later Porelo stops an earlier one. That matters for a project's repository: once one
  member's newer Porelo writes a new key, every member must update before their next start. The refusal says the key is unknown to this version and
  that a later one may know it. Whether a file should name the least version it needs is the operator's.
- **The source comments of a draft** (where a value came from, what a plugin said) leave the text the generator builds. They go to:
  - the start's output, as `notes` already do ([B455](README.md#b455));
  - comment lines SnakeYAML writes from the model's nodes, which SnakeYAML 2 can emit. Still SnakeYAML, not hand-built text. **To be shown working
    in the measurement** before it is chosen.
- **C4, editing that keeps comments:** it can stay, but not as a text patch. An edit loads the node tree with comments kept, changes the model's
  node, checks the model, and dumps the tree. Comments and key order stay; quoting and blank lines may not stay byte for byte. The measurement
  shows what a round trip of each example in `doc/project-file.md` changes, and the operator decides whether that is enough.
- **The window's schema** (`ProjectFileSchema`) is then read from the model, not from `ProjectSchema`'s lists.

## Measurement, Before Any Code For Main

Bean Validation is expected; another way only if the numbers speak against it.

**Three builds of the native `porelo`,** from one commit of `main`, on the same machine, with the pinned GraalVM, in a worktree of their own. Nothing
from them goes to `main`.

- **V0:** `main` as it is.
- **V1:** V0 plus a slice of the model, as records, one type for each level, with one validator class of ours in plain Java. The slice is `project`, `image`, `egress`,
  `limits` and `repositories.<name>` with their fields. It is bound from SnakeYAML's node tree, which carries line numbers, and called once on the
  path of `porelo project show` for a file.
- **V2:** V1 with Jakarta Bean Validation instead of our checks: Hibernate Validator 9.0.1 on `jakarta.validation-api` 3.1.1, annotations on the
  records, one class of ours for what crosses fields. `ParameterMessageInterpolator`, so no Expression Language is pulled in; if it is needed after
  all, that is measured too.

**Measured for each:**

1. Size of the executable, in bytes.
2. Start time:
   - `porelo --version`, 50 runs: median and the 90th percentile;
   - reading the largest example of `doc/project-file.md`, the same.
3. Peak memory of that read, by `/usr/bin/time -v`.
4. Time and peak memory of the `native-image` build.
5. Reachability metadata: what the tracing agent records over the slice's tests, by the existing agent step in the root `pom.xml`, counted by kind.
   Whether Hibernate Validator needs entries we must keep for each record, beyond what GraalVM's reachability metadata repository gives.
6. Behavior in the native image: an invalid file is refused with the same message as on the JVM. A missing entry fails silently in a native image,
   so this is checked, not assumed.
7. The round trip of every example in `doc/project-file.md` through the node tree, with comments: the diff, for C4.

**Reported:** a table of V0, V1 and V2, the round trip's diffs, and a recommendation. No threshold is set beforehand; the operator weighs the numbers.

## Parts, After The Measurement

Sizes are said again then:

- the model and its reader with one check, and `ProjectSchema` folded into it;
- writing, `ProjectProposal` and `DefaultProject` on it;
- edits on the node tree, `EgressEdit`;
- `porelo.yml` and `ProjectFile`;
- the docs, and the window's schema.
