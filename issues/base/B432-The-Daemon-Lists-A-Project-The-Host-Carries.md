# B432 — The Daemon Lists A Project The Host Carries

**Status:** Idea.

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

## The shape

- `ProjectInventory.projects()` adds `hosted(<account>)`: a few lines and a test.
- Before the first take there is no clone, so `fileOf` answers no path for that row, and an interface acting on a project
  reads it by that path. The row needs to say so, and a start takes the project as it does today.
- The daemon does not take a project when it lists. That would fetch at every read, and on a host replace the person's forge
  token each time.

## Acceptance

- On a host with a carried project and no first start, `Projects` names it, with no fetch and no token asked.
- A person the host's file does not name sees no such row, and learns no name of a project they are not in.
- A locked person's project goes as `project list` lets it go.
- After a first start the row is the one it is today.

## To be checked

- What an interface needs from a row with no path, with its owner, before the row's fields are settled.
