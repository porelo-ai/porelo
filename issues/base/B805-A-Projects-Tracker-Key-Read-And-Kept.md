# B805 — A Project's Tracker Key Read And Kept

```json
{ "$schema": "issue-1.json", "id": "B805", "title": "A Project's Tracker Key Read And Kept", "state": "Review",
  "size": "M", "priority": "High", "rank": 4, "blockedBy": [],
  "covers": "`project.yml` takes the top-level key `tracker` the person's planner writes (which store holds the issues); porelo checks its shape, keeps it through every rewrite of the file, documents it, and uses it for nothing else" }
```

**Why this size.** A new key in the project file model (`core`), its checks, schema entry, round trip and docs; a
contract the person's planner writes, so M rather than S.

**What must be true.** A person's planner that keeps a project's issues outside the repositories records in the
project's file which store holds them; a new issue takes the tracker's number, so no number is kept beside it; porelo
reads, signs, shows and carries that file as before. Today any key
porelo does not know is refused where it stands (`Binding.section`), so a file with such a key no longer reads and
every start of the project is refused.

## Plan

- **The contract**, agreed with the planner; the key is optional, at the top level of the project's file and never in
  a repository's own `porelo.yml` or `.porelo.yml`, and porelo uses it for nothing:

  ```yaml
  tracker:                 # which store holds the issues; absent means the Markdown store in Git
    kind: github
    project: porelo-ai/7   # <owner>/<number> of the GitHub project holding the fields
  ```

  `tracker`: open like `extensions`, so a new store needs
  no change here; absent is no tracker, written empty is refused for want of `kind`; `kind` is a lower-case word
  with hyphens, a letter first; every setting is named by lower-case letters, digits and `_`, holds one line of at
  most 512 characters, and is never an address with a user or password in it, which is refused without repeating
  the value. It holds at most 256 entries.
- **Built** (`core`): `Sections` lists it at the top of the project file and leaves it out of a repository's
  settings; `RepositorySettings.theProjects` refuses it in a repository's file by name; `ProjectFile` carries a
  nullable `tracker`, with a constructor without it for every existing caller; `FileBinder` binds it;
  `ProjectFileCheck` holds the rules; `FileYaml` writes it back in the order read; `ProjectSchema` describes it and
  `ProjectReader.openSections` names it. A key for the highest number each issue prefix used is not built: a new
  issue takes the tracker's number, and a carried-over one keeps its old id only as a fixed alias.
- **`project show`** lists no setting of the file one by one, `readings` and `extensions` neither, so it does not
  show it; nothing was added there.
- **Signing**: in an organization's project it is signed with the file like every other key; the planner's
  carry-over ends by telling the person to run `porelo project sign`.
- **Docs**: `doc/project-file.md` gets the key with its rules in the example `FileExamplesTest` reads, and names
  it among what a repository's file may not set.
- **Tests**: `PlanningKeysTest` - a file with it reads as a project, keeps it in order through a rewrite, refuses
  every malformed entry by its path, refuses an address with a credential without showing it, and refuses it in a
  repository's file; seen red with the repository refusal and the credential rule broken.

## Acceptance

- A project file with `tracker` reads, starts tasks and is signed as before, and keeps it
  unchanged through a rewrite; a malformed entry is refused with its line.
- `doc/project-file.md` names the key and its rules.
