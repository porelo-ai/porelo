# [B479](README.md#b479) — A Host Carries Single Repositories Of A Project

**Status:** Review. Accepted and set to Backlog on the operator's word (2026-11-06).
**Size:** M - a second entry form in a host's `host.yml`, its judging, both verbs and what reads a host's projects;
their tests. Estimate 150 minutes.

**What must be true.** A person puts one repository of a project on a host and takes it off again; the whole project
stays the comfortable form when all are needed. The organization's files say which repositories a host carries, the
reader judges it as it judges a whole project today. Each read key at the central forge is deleted by whoever made
it, by the id it kept: the host its per-repository keys, Porelo the organization's. porelo-host's part (the follow takes only the named repositories, and
removes one taken off, with its deploy key) and the window's part (choose repositories when carrying, take one off)
are filed in their repositories.

## Found

- A host's `host.yml` holds `admins` and `projects`, a list of project names, each once (`TreeFiles.hostFile`).
- The reader allows a project newly on a host only when it stands and is on no other host (`TreeWalk.carriable`); the
  projects a host carries are its admin's to change, not an organization admin's.
- `porelo org host carry HOST PROJECT` and `uncarry` write that list (`OrgHostCommand`, `GitTreeWrites`); what a
  host carries is read back through `OrganizationQuestions` and `OrganizationProjects.host` / `hosted`.
- `porelo org add-read-key` adds a host's read-only deploy key with a token on standard input; nothing removes one.
- The organization's files do not know a project's repositories: those are in its `project.yml`, in the project's
  own repository.

## Plan

1. **The entry form.** A `projects` entry is either a name (the whole project, as today) or a mapping
   `{name: P, repositories: [a, b]}`, names each once, at least one. Written back in the same form; a host file of
   names alone reads and writes as before.
2. **The reader.** A repository newly on a host must belong to a project that stands; the "on no other host" rule
   moves from the project to the repository: a whole project on a host excludes each of its repositories elsewhere,
   and one repository on two hosts is refused. Whether a repository name exists is not knowable here (the names are
   in `project.yml`); the follow refuses an unknown one, and `carry` checks it against the accepted `project.yml`
   when this machine has one.
3. **The verbs.** `porelo org host carry HOST PROJECT [--repository NAME]...`: without `--repository` the whole
   project; with it, those repositories added to the host's entry. `uncarry HOST PROJECT [--repository NAME]...`
   takes those off, or the whole project. `--json` answers the entry as written and, for what was taken off, the
   read keys to remove.
4. **The read keys, settled with porelo-host (2026-11-06).** The host makes one read key per repository it takes, at
   the central forge, and keeps its id; it deletes that key itself when the repository is taken off. Porelo makes the
   organization's read key on its own repository (`org add-read-key`); `porelo org remove-read-key --settings ...
   --key ...`, the token on standard input as `add-read-key` takes it, deletes that one, for a host's down or the
   uncarry of all it carries. `uncarry --repository` deletes no key.
5. **What reads it.** `OrganizationQuestions` answers each host's entries with their repositories;
   `OrganizationProjects.Placed` gains the repositories a host carries (empty for the whole project), for the host's
   follow and the window.
- **Red tests:** a mapping entry reads and writes back; one repository on two hosts is refused at its path; a whole
  project on one host and its repository on another is refused; `carry --repository` twice adds, `uncarry
  --repository` of the last one removes the entry; an old host file with names reads unchanged.

## Open questions

- Whether one project may be spread over several hosts by its repositories (assumed yes), or a project's
  repositories stay on one host and only a subset is carried.
- Whether the gate and a task on a host need the project's other repositories at all (a task names one repository; a
  project-wide task does not exist today).

## Done

- **The entry form** (`TreeFiles.hostFile`, `hostFileText`): an entry of `projects` is a name or
  `{name: P, repositories: [a, b]}`, each name once, at least one repository. It is written back in the same form,
  and a host file of names alone reads and writes as before. The trust record keeps a host's repositories, and one
  from before reads as whole projects.
- **The reader** (`TreeWalk.carriable`): an entry new or changed must name a project that stands. A project carried
  whole keeps each of its repositories off every other host, and a repository is on one host at most. What a host
  carries, its repositories included, stays its admin's to change.
- **The verbs** (`OrgHostCommand`): `carry HOST PROJECT [--repository NAME]...` adds those repositories to the host's
  entry, or carries the whole project; `uncarry` takes them off, the last of them with the project. `--json`
  answers `project` and `repositories`, from which the host deletes its read keys. `uncarry --repository` of a
  project carried whole is `refused-state`. A name no repository can have is refused as usage before anything is
  asked.
- **The read keys:** `porelo org remove-read-key --settings FILE --key FILE`, the token on standard input
  (`ForgeReadKey.remove`, by the listed id, the comment ignored). Not there is `removed: false`, so a retry is
  harmless. `add-read-key` and it share their reading of the token and the key (`OrgReadKeyCommand`).
- **What reads it:** `OrganizationQuestions.Host.repositories`; `OrganizationProjects.Placed.repositories` from a
  host's file, none for the whole project or a file from before.
- **Open questions, as assumed:** one project may be spread over several hosts by its repositories. `carry` does not
  check a repository name against an accepted `project.yml`: the organization's files do not know a project's
  repositories, and the host's follow refuses an unknown one.
- **Tests:** in GitTreeStoreTest, GitTreeWritesTest, OrgWritingCommandsTest, OrgRemoveReadKeyCommandTest and
  OrganizationProjectsHostTest. Each was seen red with its rule broken: the reader's two exclusions (3 fail), the
  write-back and adding up (2), the name check (2), the key match (3), the host file's repositories (2).
