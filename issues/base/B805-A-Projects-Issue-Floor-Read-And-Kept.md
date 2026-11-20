# B805 — A Project's Issue Floor Read And Kept

```json
{ "$schema": "issue-1.json", "id": "B805", "title": "A Project's Issue Floor Read And Kept", "state": "Analyzed",
  "size": "M", "priority": "High", "rank": 4, "blockedBy": [],
  "covers": "`project.yml` takes a top-level `issue_floor`, issue prefix to the highest number it ever used; porelo checks its shape, keeps it through every rewrite of the file, shows it and documents it, and uses it for nothing else" }
```

**Why this size.** One new key in the project file model (`core`), its check, schema entry, round trip and docs; a
contract the person's planner writes, so M rather than S.

**What must be true.** A person's planner that numbers issues outside the repositories records, per issue prefix, the
highest number the prefix ever used, so a new issue never takes a number an older one had. It keeps that in the
project's file, and porelo reads, signs, shows and carries that file as before. Today any key porelo does not know is
refused where it stands (`Binding.section`), so a file with such a key no longer reads and every start of the project
is refused.

## Plan

- **The contract**, as proposed to the planner: a top-level key `issue_floor`, a mapping from an issue prefix (one or
  more capital letters, as `repositories.<name>.issues` spells them) to a whole number of at least 0, the highest
  number that prefix ever used. Optional; absent means no floor. A prefix no repository lists any longer is allowed,
  since a retired prefix's numbers stay used. porelo uses the numbers for nothing: they are the planner's.
- **`core`**: `Sections` lists `issue_floor` at the top level of the project file (not of a repository's own file);
  `ProjectFile` carries it as an ordered map; `FileBinder` binds it with `named`; `ProjectFileCheck` refuses a key
  that is not a prefix and a value that is not a whole number of at least 0, each with its line; `FileYaml` writes it
  back in the order read, so a rewrite (`project sign`, an edit by the window) keeps it; `ProjectSchema` describes it.
- **`project show`** prints it beside the other top-level settings, or says where it does not; to be read.
- **Signing**: in an organization's project the key is signed with the file like every other key; the planner's
  carry-over that writes it is followed by the person's `porelo project sign`. Nothing new is built for that.
- **Docs**: `doc/project-file.md` gets the key with its rules.
- **The test that goes red first**: `FileExamplesTest` - a project file with `issue_floor: {B: 805, P: 40}` reads
  and lists no fault; then `ProjectFileFaultsTest` for a lower-case prefix and a negative or fractional number, and
  `FileRoundTripTest` that a written file keeps the mapping as read.

## Acceptance

- A project file with `issue_floor` reads, starts tasks, is signed and shown as before, and keeps the key unchanged
  through a rewrite; a malformed entry is refused with its line.
- `doc/project-file.md` names the key and its rules.

## To be checked

- The planner's agent confirms the key's exact shape: its name in the file's own spelling (`issue_floor`, not
  `issueFloor`), the top level, and whether a retired prefix must be accepted.
- Whether `project show` lists top-level settings one by one or only some of them; read in its command.
