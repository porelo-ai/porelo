# B805 — A Project's Planning Keys Read And Kept

```json
{ "$schema": "issue-1.json", "id": "B805", "title": "A Project's Planning Keys Read And Kept", "state": "Implementing",
  "size": "M", "priority": "High", "rank": 4, "blockedBy": [],
  "covers": "`project.yml` takes two top-level keys the person's planner writes, `issue_floor` (issue prefix to the highest number it ever used) and `tracker` (which store holds the issues); porelo checks their shape, keeps them through every rewrite of the file, shows and documents them, and uses them for nothing else" }
```

**Why this size.** Two new keys in the project file model (`core`), their checks, schema entries, round trip and docs;
a contract the person's planner writes, so M rather than S.

**What must be true.** A person's planner that keeps a project's issues outside the repositories records in the
project's file which store holds them and, per issue prefix, the highest number the prefix ever used, so a new issue
never takes a number an older one had; porelo reads, signs, shows and carries that file as before. Today any key
porelo does not know is refused where it stands (`Binding.section`), so a file with such a key no longer reads and
every start of the project is refused.

## Plan

- **The contract**, agreed with the planner; both keys are optional, at the top level of the project's file and never
  in a repository's own `porelo.yml`, and porelo uses neither for anything:

  ```yaml
  issue_floor:             # issue prefix -> the highest number it ever used
    B: 805
    P: 40
  tracker:                 # which store holds the issues; absent means the Markdown store in Git
    kind: github
    project: porelo-ai/7   # <owner>/<number> of the GitHub project holding the fields
  ```

  `issue_floor`: a prefix is one or more capital letters, as `repositories.<name>.issues` spells them, and one no
  repository lists any longer is allowed, since its numbers stay used; a value is a whole number of at least 0.
  `tracker`: a mapping whose values are one-line strings, open by design like `extensions`, so a new kind of store
  or a new setting of one needs no change here; `kind` is required within it, one word of lower-case letters,
  digits and hyphens. Anything else in either is refused with its line.
- **`core`**: `Sections` lists both at the top level of the project file and leaves `tracker`'s own keys open, as
  for `extensions`; `ProjectFile` carries both as ordered maps; `FileBinder` binds them with `named`;
  `ProjectFileCheck` holds the rules above; `FileYaml` writes them back in the order read, so a rewrite
  (`project sign`, an edit by the window) keeps them; `ProjectSchema` describes them, and its class comment names
  `tracker` among the places open by design.
- **`project show`** prints them beside the other top-level settings, or says where it does not; to be read.
- **Signing**: in an organization's project both are signed with the file like every other key; the planner's
  carry-over that writes them ends by telling the person to run `porelo project sign`. Nothing new is built for that.
- **Docs**: `doc/project-file.md` gets both keys with their rules.
- **The test that goes red first**: `FileExamplesTest` - a project file with both keys as above reads and lists no
  fault; then `ProjectFileFaultsTest` for a lower-case prefix, a negative or fractional number, a `tracker` without
  `kind` and one whose value is not a string, and `FileRoundTripTest` that a written file keeps both as read.

## Acceptance

- A project file with `issue_floor` and `tracker` reads, starts tasks, is signed and shown as before, and keeps both
  unchanged through a rewrite; a malformed entry is refused with its line.
- `doc/project-file.md` names both keys and their rules.

## To be checked

- Whether `project show` lists top-level settings one by one or only some of them; read in its command.
