# B451 — An Ecosystem Adapter Is Not A Plugin

**Status:** Review.
**Size:** S - words in `doc/` and in what `porelo` prints.

**What must be true.** What a person reads says "ecosystem adapter", and "Maven adapter" for that one, as "plugin" reads as a Maven
plugin. The operator's word, after the test and before the first release fixes the word.

- `doc/`, with `doc/ecosystem-adapters.md` and its links, and the words `porelo` prints, in
  [B444](README.md#b444)'s first start among them.
- `porelo ecosystems` stays as the command, and the package stays `porelo-ecosystem-maven`.
- `plugins/` in the other repository, and what an installed path or the protocol holds of the word, are Agent Ecosystems' issue.
  Porelo reads those paths, so this goes with theirs where a path changes.

## Read By Agent Ecosystems, In `EC11`

An upgrade moves nothing for a person: the installed paths do not hold the word, and in the protocol it stands only in comment lines.
For this issue:

- `porelo project init --json` has a field `"plugin"` on each finding. Renaming it changes a JSON answer, so whoever reads it moves too.
- The six comment lines in the `.varlink`, which change nothing on the wire, by their reading; to be judged here.
- `porelo ecosystems --help`.
- `doc/decisions.md`'s anchor `#a-plugin-has-nothing-to-secure`, if anything links to it.

The word stays where it means Maven's own, so this is read line by line, never replaced in bulk.

## Plan

- Rename the ecosystem guide and update navigation, README links, related issue paths, and unreleased
  entries; retain “plugin” where it means Maven's own, IntelliJ's, or Cucumber's.
- Update ecosystem discovery help, empty discovery, first-start notes and warnings, project-init
  diagnostics and its plain discovery label, and the protocol upgrade diagnostic.
- Change only prose in the ecosystem and first-start varlink descriptions. Keep installed paths,
  package names, command names, protocol versions, JSON `plugin` and `plugins` keys, and `NO_PLUGIN`.
- Guard existing JSON readers with a command-level compatibility test: deliberately rename the
  discovery field, observe a failing assertion, restore it, and run the affected unit tests.
  Remove assertions holding only the former wording; no wording-only tests are added.

## Decisions

- A field rename would break readers for no functional benefit. `project init --json` retains
  `plugins` and each finding's `plugin`; its plain output labels discovery as `adapters`.
- The six ecosystem varlink comment lines and the first-start comments describe adapters; no wire
  declaration changes, so no adapter package or desktop reader migration is required.
- The decision anchor named above is absent from the checkout, including its links. Existing Maven
  build-plugin and IntelliJ-plugin terminology stays.

## Acceptance

- The JSON compatibility test fails against a compiling field-rename mutation and passes with the
  existing fields restored. Formatting, affected modules' unit tests with dependencies, and citations pass.
- A machine run would exercise `project/project-first-start.feature`, scenario “a yes writes
  project.yml; the same file from the same upstream is that project, and nothing else is”.
  It is not run in this worktree; ecosystem help and the empty listing can also be checked there.

## Remaining verification and external work

- The repository-named skills are not supplied in this session; their fetch requires network access
  outside Maven repositories and is not permitted. No skill-version compliance is claimed.
- The adapter repository must rename its own `plugins/` source directory and its own human-facing
  documentation and diagnostics to adapters, keeping package names and installed ecosystem paths.
  No code in another repository or desktop window is changed here.

- Focused first-start, proposal, JSON-compatibility, discovery, detection, file, and version-boundary
  unit tests pass. The full unit command stops in the unchanged wire socket tests because the
  sandbox maps `/` and `/tmp` to an unrelated uid. Redirecting JVM temporary files into the
  worktree leaves the same refusal at `/`; no socket ownership check is weakened.
- Read-only review found one remaining adapter reference in `wire/README.md`, now corrected, and
  no functional or compatibility finding. The full unit run still needs an environment whose filesystem ancestors belong to root or
  the current account.
