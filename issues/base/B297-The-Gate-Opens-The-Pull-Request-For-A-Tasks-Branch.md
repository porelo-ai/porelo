# B297 — The Gate Opens The Pull Request For A Task's Branch

**Status:** Analyzed; accepted on the operator's word on 2026-10-27.
**Size:** M - one contract method with its version step, the forgejo adapter's implementation and recordings, the
call from the task's builds helper, the start report's line, a doc line.

**What must be true.** When an `online` task's gate first passes its work on to `porelo/<task>` at a forge that the
project's `builds:` reads, Porelo opens one pull request from `porelo/<task>` into the repository's default branch, as
the gate's own forge identity and with the credential the gate already lends, and names its address to the task and
in `task status`. A second push opens no second pull request, and the person - who alone may approve and merge - is
never its author.

## Why

Found in the sprint-5 test on the rented host, 2026-10-27. `doc/way-of-working.md`, "With the host's own forge",
step 3, has the person review "the task's branch and its build in a pull request in Forgejo", but nothing opens one:
the gate pushes `porelo/<task>` and stops, and no code in `porelo` or `porelo-adapters` calls a forge's pull-request
API. The person opening it does not work either: porelo-host's [HO31](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho31) lets only the person approve and merge on `main`,
and Forgejo refuses an author's approval of their own pull request. In the test an agent's forge account had to open
it by hand.

porelo-host already plans for this: the gate's `porelo-<account>` in the team `gates` has pull-request write
(`roles/forge/tasks/gate.yml`). The task cannot open it - it holds no forge credential, by design.

## Plan

Read in the code on 2026-10-27.

1. **The contract** (`builds/api`, `org.fuin.porelo.Build2.varlink`): a method `OpenPullRequest(upstream, api,
   token, branch, title) -> (url: string, opened: bool)` that opens a pull request from `branch` into the
   repository's default branch, or answers the open one from that branch with `opened: false`. Under
   [B224](README.md#b224)'s rule the contract only grows: an adapter that predates it answers "cannot say", and
   Porelo then says once that this forge's adapter opens no pull request, and goes on.
2. **porelo-adapters, forgejo first:** `GET /repos/{owner}/{repo}/pulls?state=open` filtered by head, then `POST
   /repos/{owner}/{repo}/pulls` with `head`, `base` (the repository's `default_branch`) and `title`. Recordings for
   opened, already open, refused (401/403 → `CredentialRefused`), no such repository. github follows in its own
   step; http answers "cannot say".
3. **Who calls it:** the task's builds helper (`BuildsWiring` / `TaskWatchBuildsCommand`), which already reads the
   branch through the same adapter with the credential `builds:` names, calls it once when `Head` first finds
   `porelo/<task>` at the upstream. The title is the branch's first commit subject. The address goes into the
   task's `/porelo/files` beside the build verdicts and into the task's state, so `task status` shows it.
4. **The start report** says `pull request   opened by the gate when the first push reaches porelo/<task>` for an
   online task whose forge reader is installed.
5. **Docs:** `doc/way-of-working.md` step 3 says the gate opens it; the line in "Not built yet" goes.

**Red first:** an adapter contract case that opens a pull request against a recording and one that finds it already
open; a `TaskWatchBuildsCommand` test where the first head opens it and the second does not.

## Acceptance

- On a host with porelo-host's forge, an `online` task's first push leaves an open pull request `porelo/<task>` →
  `main`, authored by `porelo-<account>`, and the person can approve and merge it under [HO31](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho31).
- A second push of the same task opens nothing new, and `task status` names the one address.
- With an adapter that predates the method, the task runs as today and the start says once that no pull request is
  opened.

## To be checked

- Whether `builds:`'s credential (read-only would do for the reader today) is always the gate's token with
  pull-request write, or the open needs the gate's credential for the upstream instead; on porelo-host they are the
  same vault entry, `git.token.<forge host>`.
