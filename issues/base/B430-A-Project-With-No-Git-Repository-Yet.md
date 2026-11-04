# B430 — A Project With No Git Repository Yet

**Status:** Idea.

**What must be true.** The command line says what happens: the project's Git repository is created, and its first
`project.yml` is signed in the same step. A person adding a project with themselves as its admin can do it in one step.

## Why

A project with no repository stands on the organization's page and not under a person's projects, and only its named
Project Admin can act on it. "Open" does not say what the command does.

## The shape

- **`porelo org` already answers it.** Each project's row carries `opened` (`OrgCommand.project`), read from the
  organization's tree with no call per project, so an interface needs nothing new to place a row. It says a filed address,
  not a repository on the forge: a run that stopped after making the repository and before filing its address still reads
  as not opened, and the step asked again finishes it. `stale` still applies: a stale read must not say a repository is
  missing. The field's name changes with the words, or stays.
- **The words.** The command, its description, `ProjectAddressCommand`, the sentence in `GitTreeWrites` a host's reader sees,
  and `doc/commands.md`. The first commit's subject may change, since a retry finds that commit by id and signature.
- **The old name** stays as a hidden alias for a release, since an interface and a Porelo of different builds meet.
- **The refusal `not-opened`** is named in `doc/commands.md` and `doc/project-file.md`, and no reader was found in the
  repositories on this machine; it changes with its documents, or stays.

**One step, where the person adding a project names themselves as its Project Admin:**
`porelo org project add PROJECT --admin LOGIN --create-repository [--review required|optional]`.

- The named admin must be the person acting; otherwise it is refused before anything is written.
- It is not atomic: the forge can refuse after the project was added. That leaves a project with no repository, which has a
  place, and the refusal says the project was added and what is left.
- One `--id` covers it: the ids of the two changes derive from it, and the second stands on the revision the first made,
  or it answers `moved-on`.
- The two commands stay for an admin who is someone else.

## Acceptance

- With `--create-repository`, a forge refusing halfway leaves the project standing, says so, and the same id finishes it.
- Naming another person as admin writes nothing.

## Open questions

- The command's new name: `porelo project create-repository PROJECT` is proposed; `project init`, `project repository` and
  `project address` already mean something else.
- Whether the one step is wanted, and whether the field `opened` is renamed with the command.
