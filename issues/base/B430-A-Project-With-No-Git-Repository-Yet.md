# B430 — A Project With No Git Repository Yet

**Status:** Implementing.
**Size:** M - a command renamed and a contract the window reads, several modules, and the one step.

**What must be true.** The command line says what happens: the project's Git repository is created, and its first
`project.yml` is signed in the same step. A person adding a project with themselves as its admin can do it in one step.

## Why

A project with no repository stands on the organization's page and not under a person's projects, and only its named
Project Admin can act on it. "Open" does not say what the command does.

## Plan

**The words.**

- **`ProjectOpenCommand`** becomes `ProjectCreateRepositoryCommand`, `porelo project create-repository PROJECT [--review ...]`. `open`
  stays as a hidden subcommand doing the same for a release, since an interface and a Porelo of different builds meet
  (`PoreloCli`).
- **The sentences:** the description, `ProjectAddressCommand`, `ProjectRepositoryCommand`, the sentence in `GitTreeWrites` a host's
  reader sees, `doc/commands.md`. The first commit's subject in `ProjectFile.found` may change: a retry finds that commit by
  id and signature, and the old subject stands in repositories already made.
- **The JSON stays:** `opened` in `porelo org`'s rows and `project show`, `not-opened` as a refusal and a state. They are machine
  words; `opened` says a filed address, so a run stopped after making the repository reads as not opened, and the step
  asked again finishes it. A stale read must not say a repository is missing.

**One step, where the person adding a project names themselves as its Project Admin.**

- `porelo org project add PROJECT --admin LOGIN --create-repository [--review required|optional]`, in `OrgProjectCommand.Add`. The two
  commands stay for an admin who is someone else.
- The named admin must be the person acting; otherwise it is refused before anything is written.
- The second part is the same steps as the command's, moved to where both call them, and reads the organization anew, so the
  admin check passes on the revision the first part made.
- One `--id` covers it: the second change's id derives from it, so asking again under the same id finds each part's own work.
- It is not atomic: the forge can refuse after the project was added. That leaves a project with no repository, which has a
  place, and the refusal says the project was added and what is left.

**Agreed with the interface before the rename lands:** the verb it calls, and that its token preparation goes before the one
step.

**Red first:** `ProjectOpenCommandTest` under the new name, and a test that naming another person as admin writes nothing.

## Acceptance

- With `--create-repository`, a forge refusing halfway leaves the project standing, says so, and the same id finishes it.
- Naming another person as admin writes nothing.
- `porelo project open` still works and is not in the help.
