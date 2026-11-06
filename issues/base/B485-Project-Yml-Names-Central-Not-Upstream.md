# [B485](README.md#b485) — project.yml Names `central`, Not `upstream`

**Status:** Review. A Bug, Accepted on the operator's word (2026-11-06).
**Size:** S - the key in the reader, the writer, the check and `project verify`; the docs. Estimate 60 minutes.

**What must be true.** `project.yml` names each repository's central forge as `central:`, and the project's own
repository as `project.central:`. It names no host's address: a host derives its own. `upstream:` is the old name:
read alike, so a file not yet renamed keeps working, and `porelo project verify` says to rename it without refusing
it, so no host stops following such a file. Porelo writes only `central:`.

## Found

- A row held two addresses: `upstream`, where approved work goes, and `central`, where a host takes the repository
  from, with a check that both end in the same name. A move to a host wrote the host's loopback address into
  `upstream:`, which is meaningless anywhere else.
- `ProjectFileCheck` runs on every read (`ProjectFileReader`), not only in verify, so a refusal there would stop every
  project not yet renamed. `porelo project verify` is what a host runs at each follow.

## Done

- A row naming `central:` alone sends its work there, and a host takes the repository from it. A row not yet renamed
  keeps what it said: its `upstream:` is where work goes and names no central, so a host places it as before.
  `FileBinder` reads `project.central` before `project.upstream`. `FileYaml` writes `central`, and an old `upstream`
  only where it differs from the row's central, so a file read and written keeps its meaning. The rows Porelo
  creates (`project init`, `project repository add`, the default project) name one address, as `central:`. The check
  names `.central` in its faults; the rule that two addresses end alike is gone.
- `OldNames.of(text)` lists the keys still under their old name; `ProjectFile.Read` carries them, and `porelo project
  verify` answers them as `renames` and says "Rename ... to central." in its text. Nothing is refused.
- The schema describes `central` and marks `upstream` as its old name; `doc/project-file.md` and
  `doc/getting-started.md` show `central:`.
- Unchanged tonight: `HostForgeAddress`, the CLI's `--upstream` options and the `upstream` fields of answers. A
  default-project row now names its address as `central:`, as ordered, so a host reaches it on its own forge where
  it refused it before.
- Tests: a row naming `central` alone sends work there, and one not yet renamed keeps its work address and names no
  central (`HostForgeAddressTest` and `OrgReadingCommandsTest` were red while an old `upstream` counted as central);
  verify answers `renames` for an old file and none for a renamed one (red with `OldNames` finding nothing); the
  entries init and repository add show say `central:`; the fault table and the reader's messages name `central`.
