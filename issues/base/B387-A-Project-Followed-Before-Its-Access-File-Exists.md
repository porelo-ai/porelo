# B387 — A Project Followed Before Its Access File Exists

**Status:** Idea.
**Severity:** high.
**Size:** S, proposed - the follow takes an access repository with no commit as one with no access commit yet, and
`project access init` writes the first, with a test of the whole start from an empty repository.

**What must be true.** A project admin can start a project with an access repository using Porelo's own commands
alone: follow the project, then write its first access commit with `porelo project access init`.

Today the two wait on each other. `porelo project follow NAME URL --signed-by FP` refuses (70) a project whose
access repository has no commit yet ("cannot fetch ...: could not find remote ref HEAD ... nothing was recorded"),
and `project access init` writes only for a followed project and only into an empty access repository. The first
access commit can only be written by hand. Measured on the Ubuntu VM with a throwaway key held only in an ssh-agent:
a `project.yml` naming its signer and `access: {repository: ...}`, committed and SSH-signed through the agent with
plain git, follows only once the access repository holds a commit.

## Acceptance

- A test follows a project whose access repository is empty, with no access commit in force, then runs
  `project access init`, and the follow takes that first commit; seen to fail first.
- An access repository that cannot be reached at all is still refused, as it is today.
