# B387 — A Project Followed Before Its Access File Exists

**Status:** Review.
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

## Done

- `Reconcile.reconcileAccess` takes an access repository that was reached and lists no ref at all (`git ls-remote`,
  asked with the credential the fetch lends) as one with no access commit yet: the follow publishes with the access
  outcome `unchanged` and no access commit, and nothing reads access settings until one is applied. The next follow
  takes the first commit `project access init` writes, checked as a first follow checks one.
- Only when the follow record holds no access commit at all and the fetch failed as unreachable: an accepted
  rewrite, an access file moved to another repository, an emptied repository, one that is not there, a shut vault,
  an unknown host key or a missing credential are refused as before.
- Tests in `AccessReconcileTest`: the start from an empty repository, with access settings withheld until the
  first commit; an emptied repository under an access commit in force refused, with and without
  `--accept-rewrite`; a repository that is not there refused and nothing followed. Each guard seen red, the
  `--accept-rewrite` case on the first version of this fix.
- `project verify` answers an access repository with no commit, given without `--access-since`, with exit 0, an empty
  `access_commit` and nobody in `persons`, `admins` and `certifiers`, so a program registering the project files
  the first access commit; with `--access-since` it is refused as before (`ProjectVerifyCommandTest`, red first; the
  guard seen red under a mutant). Built during the sprint 6m test, with its short unit tests only.
- After its one reading (agy Gemini): the empty case is asked of the follow record, not of what an accepted rewrite
  takes as in force, and only after an unreachable fetch. Left: `doctor` answers a project with no access commit
  yet as followed without saying so; `registry joinable` answers an empty access repository as unreachable; the
  probe runs with the fetch's own environment, which is the follow's and not `project access`'s hardened one.
- Found in the sprint 6m walk: the access file `project access init` wrote named no `source`, so `registry joinable`
  refused the project ("the access file names no project repository"). `project access` now writes the address the
  project is followed from into a file that names none, if `source` takes it; an existing `source` is kept, and a
  path or `file://` writes none (`ProjectAccessCommandTest`, red first; the address guard seen red under a mutant).
  Short unit tests only, during the walk.
