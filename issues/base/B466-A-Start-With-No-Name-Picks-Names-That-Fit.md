# B466 — A Start With No Name Picks Names That Fit

**Status:** Review.
**Size:** S - two naming methods and one refusal in `app-model` and `runtime`, with their unit tests; no contract.

**What must be true.** `porelo task start` with no name never picks a project and task name whose container name is longer
than the 64 characters Porelo allows; when the names a person gave cannot fit, the refusal names a command that works.

## Found

In an operator's test on 2026-11-06, in a repository with no `project.yml`, under WSL. A start with no name offered to write
`project.yml` for project `<repository>-project`, was told yes, and took the repository's name as the task's. With a
repository name of 26 characters, such as `example-shop-api-templates`, the start then failed while reading the project and its
source:

    'porelo-example-shop-api-templates-project-example-shop-api-templates' has 68 characters, and a container name may have 64

Porelo chose both names itself and they break its own limit. An explicit short task name works.

Read in the code: `ProjectProposal.projectName` caps the proposed project name alone at 63 characters, while
`ContainerName` checks `porelo-<project>-<task>` against 64, so a repository name of about 25 characters or more cannot get
a task by default.

## Plan

The container name is `porelo-<project>-<task>`, at most 64 characters (`ContainerName.MAX_LENGTH`), so a project and a task
share 56.

- **The proposed project leaves a task room.** `ProjectProposal.projectName` cuts the repository's part at 32 characters
  instead of 55, so a proposed project has at most 40 and a task at least 16. The cut takes the last whole word before the
  limit where there is one, then drops trailing hyphens, as today. An existing `project.yml` is never changed.
- **The default task name fits.** `TaskNames.fromRepository` takes the room left, `64 - 8 - project.length()`, and cuts the
  base to it, at a word boundary where it can, dropping trailing hyphens; the `-2`, `-3` of a second unnamed task is counted
  inside the room, so the base is cut further for it. A base cut to nothing becomes `work`, as an empty one does today. The
  line `task ... - named after its repository` already shows the chosen name.
- **A cut is never a name the start refuses.** A cut that is digits only, reserved, or begins `porelo-` becomes `work`, a dot
  in the repository's name becomes a hyphen, and a proposed project cut back to a word never takes the repository's own
  name.
- **The refusal names what fits.** The length refusal in `ContainerName.refusal` gains the room left and a name that fits:
  `... a container name may have 64 so that its sockets fit a unix socket path: give a task name of at most 22 characters,
  as in 'porelo task start example-shop-api'`. Where the project leaves no room for even one character (a hand-written
  name over 55), it says the project's name in `project.yml` is too long for any task instead. Every caller - `TaskLaunch`,
  `StartCheck`, `TaskRunCommand --host` - renders that one sentence, so the CLI and the daemon say the same.
- **Red first:** in `TaskNamesTest` (new) and `ProjectProposalTest`, a 26-character repository name gives a project and a
  default task whose container name passes `ContainerName.refusal`; today it is refused at 68. In `ContainerNameTest`, the
  refusal for a too-long task names a task that the same method then accepts. Each is seen to fail before the change.
- **Checked by hand on the Ubuntu VM in `core`:** a start with no name in a fresh checkout with a 26-character name and no
  `project.yml`, answered yes, starts its task.

## Acceptance

- A start with no name in a repository whose name is long enough to break the limit today starts a task whose container
  name fits, and says which task name it chose.
- A start whose given names cannot fit is refused before anything is written, and the refusal names a command with a task
  name that fits.
