# B432 — The Daemon Lists A Project The Host Carries

**Status:** Implementing.
**Size:** M - one contract change other repositories read, in two modules, with its documents.

**What must be true.** A project a host carries for a person is in the daemon's `Projects` before its first start, as it is
in `porelo project list`. An interface that reads the daemon can then offer that start.

## Why

A bug: `doc/commands.md` says "A project your organization names needs nothing first: `porelo project list` shows it".
That holds for the command and not for the daemon.

- `ProjectListCommand` calls `OrganizationProjects.hosted`, its only caller, which reads the host's file for this person.
- `ProjectMethods`' `Projects` and `WatchProjects` read `ProjectInventory.projects()`, which holds what this account placed at
  a first start, plus registered projects, mirrors and tasks. It never asks the host's file.

Measured on a rented host: `porelo project list` named the project and the daemon's list held only `default`. No timer or
follow changes that, and an interface reading the daemon offers no start, so it waits for what only a start can do. The way
through, a start or `porelo task prepare -p PROJECT --dry-run` in a shell on the host, is said nowhere.

## Plan

- **`ProjectInventory.projects()`** adds what `OrganizationProjects.hosted(<account>)` names and it does not yet hold. Before the
  first take there is no clone, so its `file` is empty, as for a row known only from tasks left behind. `Summary` gains
  `carried`: on a host, not placed here yet. It stays false once the project is placed.
- **`ProjectMethods`** answers `carried` on the row of `Projects` and `WatchProjects`.
- **A new daemon method, `TakeProject(project)`,** calls `ProjectTaking.take` and answers the file, or the refusal's reason and
  words. An interface calls it when the person acts on a `carried` row, then goes on by the existing way with the file. A list
  never takes: that would fetch at every read, and on a host replace the person's forge token each time.
- **Documents:** the daemon's interface description, and the sentence in `doc/commands.md`, which then holds.
- **Red first:** `ProjectInventoryTest` with a host's files and no first start, expecting the row with `carried` and an empty
  `file`, and no process run.

## Acceptance

- On a host with a carried project and no first start, `Projects` names it, with no fetch and no token asked.
- A person the host's file does not name sees no such row, and learns no name of a project they are not in.
- A locked person's project goes as `project list` lets it go.
- After a first start the row is the one it is today, with `carried` false.
- `TakeProject` on a carried project gives the file and the row then has it; on a name the host does not carry it refuses and
  writes nothing.
