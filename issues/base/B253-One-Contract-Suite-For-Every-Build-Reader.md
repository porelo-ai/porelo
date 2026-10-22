# B253 — One Contract Suite for Every Build Reader

**Status:** Accepted.
**Size:** M for porelo's half - a contract suite in the build API's kit, published with it, and the
`github` reader run against it; the `forgejo` reader itself is M more, in a repository of its own.

**What must be true.** The `github` and the `forgejo` readers pass the same contract suite, each against its own
forge.

## Why

From porelo-project [PJ22](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj22), part 4: "*The
`forgejo` reader* and one contract suite in the API's kit, run against both readers." A local forge's builds are
read like GitHub's, so a task on the project's own forge hears its verdicts and logs as it does today.

Read in today's code: the contract is `org.fuin.porelo.Build1` in `builds/api` (`Describe`, `Head`, `Look`, four
errors); a reader is a package found at run time (`InstalledBuildReader`), and "nothing in Porelo names a forge"
(`builds/README.md`). The only real reader is porelo-build-github's `GitHubReader`. What a reader must do is held
today by each reader's own tests and by `builds/api`'s `FakeReader`, not by one suite both can run.

## The shape proposed

- **The reader lives in a new repository, `porelo-build-forgejo`**, as the `github` reader lives in
  `porelo-build-github`: a reader is a package, and porelo keeps naming no forge. Its issue is filed there when the
  repository is made.
- **The suite is in the build API's kit:** the cases every reader must answer the same way, run against a forge the
  test names - a branch's head and an absent branch, a commit queued, running, passed and failed, the logs of
  `failure` and of `all`, the 64 KiB and 2 MiB bounds, and each error (`RateLimited`, `CredentialRefused`,
  `NoSuchRepository`, `Unreachable`) - each from a recorded answer of the forge, so CI calls no forge.
- **Run against both:** the `github` reader against GitHub's recorded answers, the `forgejo` reader against
  Forgejo's, recorded from the local forge once ([PJ22](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj22)'s
  part 3).

## To be checked

- How a reader that differs only in its base address is told apart: Forgejo's Actions API is GitHub's in shape
  (`/repos/{owner}/{repo}/actions/runs`), so the `forgejo` reader may be the `github` one with another `api`. If it is,
  the suite proves it, and a second repository may not be needed.
- What Forgejo's API answers for a commit whose workflows have not started, and how it reports a cancelled job.
- That the project file can name a forge on the host's own network (`builds.api`) and that the reader reaches it
  over https: a reader takes the vault's token per call and keeps none of its own.
