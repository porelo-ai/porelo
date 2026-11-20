# B809 — A Project's Velocity Seed Read And Kept

```json
{ "$schema": "issue-1.json", "id": "B809", "title": "A Project's Velocity Seed Read And Kept",
  "state": "Implementing", "size": "M", "priority": "Highest", "rank": 1, "blockedBy": [],
  "covers": "`project.yml` takes the top-level key `velocity.seed.<repository>.{XS..XL}` the person's planner writes (the starting minutes per size); porelo checks its form, keeps it through every rewrite of the file, documents it, and reads nothing in it" }
```

**Why this size.** A new key in the project file model (`core`), its checks, schema entry, round trip and docs; a
contract the person's planner writes, so M rather than S, as the `tracker` key was.

**What must be true.** A person's planner that keeps a project's issues in a tracker computes each size's median from
the project's finished items, and starts from minutes per repository and size the project's file names until enough
are measured. Today any top-level key porelo does not know is refused where it stands, so a file holding it no longer
reads and every start of the project is refused; the planner writes the key only once this is published.

## Plan

- **The contract**, agreed with the planner; optional, at the top level of the project's file, never in a
  repository's own `porelo.yml` or `.porelo.yml`, and read by porelo for nothing:

  ```yaml
  velocity:
    seed:
      porelo: { XS: 30, S: 120, M: 480, L: 1440 }   # the starting median per size, in minutes
  ```

  `velocity` holds `seed` alone; each key under `seed` is one of the file's repositories; each size is one of `XS`,
  `S`, `M`, `L`, `XL`, any of them missing; each value a whole number of minutes from 1 to 100000. Everything else is
  refused by naming where it stands.
- **Built** (`core`), as `tracker` was: `Sections` lists `velocity` at the top of the project file and leaves it out
  of a repository's settings; `RepositorySettings` refuses it in a repository's file by name; `ProjectFile` carries
  a nullable `velocity` (repository to size to minutes, in the order written), with the existing constructors kept;
  `FileBinder` binds it; `ProjectFileCheck` holds the rules; `FileYaml` writes it back in the order read;
  `ProjectSchema` describes it and `ProjectReader.openSections` names it.
- **Docs**: `doc/project-file.md` gives the key beside `tracker`; changelog entry under *Added*.
- **Red first**: a `PlanningKeysTest` case reads a file with `velocity.seed`, writes it back unchanged and refuses, by
  name, an unknown repository, an unknown size, zero, a fraction, a number over 100000, a key beside `seed`, and the
  key in a repository's own file; before the build the reading case fails on the unknown key.

## Acceptance

- A project file with `velocity.seed` reads, is signed and starts its tasks as before, and a rewrite keeps the key
  exactly; each malformed form above is refused by name.
- `doc/project-file.md` and the schema describe it.
