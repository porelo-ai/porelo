# B466 — A Start With No Name Picks Names That Fit

**Status:** Idea.
**Size:** -

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
a task by default. Not yet reproduced in a test.

## Acceptance

- A start with no name in a repository whose name is long enough to break the limit today starts a task whose container
  name fits, and says which task name it chose.
- A start whose given names cannot fit is refused before anything is written, and the refusal names a command with a task
  name that fits.
