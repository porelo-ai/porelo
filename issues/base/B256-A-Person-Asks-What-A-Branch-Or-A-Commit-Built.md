# B256 — A Person Asks What a Branch or a Commit Built

**Status:** Analyzed, for sprint 3; blocked by [B253](README.md#b253).
**Size:** M - a new command group in `app-task` with one class shared with the build watch, its tests, three pages,
an acceptance feature against the stub and a changelog entry; no daemon method and no contract another repository
reads.

**What must be true.** A person asks any forge a project names what a branch or a commit built, with one command:
`porelo build status --project=<project> --repository=<repository> --branch=<branch>`, or `--commit=<sha>`, with
`--logs=none|failure|all` (default `failure`) and `--json`, `-p` and `-r` as in `porelo task start`. Project and
repository are named by their keys, never a URL: the adapter, its address and its credential come from the project
file's `builds:`, the token from the vault, and the token appears nowhere; a project without `builds:`, `default`
among them, is refused naming why.

## Why

From porelo-project [PJ62](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj62): a person
asks any of the forge adapters through one command of Porelo's. Today Porelo asks an adapter only from the helper
that follows an online task's pushes (`porelo task watch-builds`), and there is no `porelo build` command. The
command asks the adapter over the build contract's second version, so it waits for [B253](README.md#b253).

## Plan

Read in `apps/app-task` (`TaskRunCommand`, `TaskWatchBuildsCommand`, `BuildsWiring`, `BuildWatch`), `apps/app-model`
(`ProjectSource`, `WorkOrigin`), `core`'s `Builds` and `Project`, `builds/api` and `builds/stub`, on the build
contract as [B253](README.md#b253) leaves it (`Build2`, `build`, `Build.Logs.NONE`).

1. **The command group.** `BuildCommand` (`build`) with one subcommand `BuildStatusCommand` (`status`) in
   `app-task`, which already holds the build watch and depends on `porelo-build-api`; `PoreloCli` registers the group
   beside `task`. Options: `-p`/`--project`, `-r`/`--repository`, exactly one of `--branch` and `--commit` (a
   picocli `ArgGroup(exclusive = true, multiplicity = "1")`), `--logs=none|failure|all` (default `failure`), `--json`.
2. **Project and repository, read only.** `-p` resolves through `ProjectSource.require`, as `task start` does.
   Without `-p`, the checkout's origin is matched with `WorkOrigin.followedNaming`, which only reads; never
   `WorkOrigin.fromCheckout`, which adds the checkout to `default` - a question must not write a project. `-r` is
   `Project.repository(key)` with `GateSupport.repository`'s refusal; without it, the repository the checkout matched,
   else the project's own repository when it is the only one, else refused naming the keys. The forge is asked
   about `Repository.upstream`, as `BuildsWiring` does.
3. **No `builds:`, refused.** `project.builds() == null` - `default` always - is refused naming the project, that
   it has no `builds:`, and that `builds:` needs `security_class: online` (`ProjectReader`), with `doc/project-file.md`'s
   section named.
4. **One target, two callers.** `TaskWatchBuildsCommand.target()` (`:237-255`) moves to a package-private
   `BuildTarget.of(context, upstream, api, entry)` that both use: the vault opened with `context.openIfShut(true,
   true, err)` at a terminal, `readableCredentials()`, exactly the `credential` entry. A locked vault and a missing
   entry stay `BuildWatch.NotNow` for the watch; the command renders them, naming `porelo vault unlock` and `porelo
   vault put <entry>`.
5. **Asking.** The adapter is found by `InstalledBuildReader.find` in the account's and the package's directories, as
   the watch finds it, and started on a socket in a new owner-only directory under the runtime directory
   (`PoreloPaths`, beside `daemonSocket()`), removed on close. `--branch` asks `head` first; `""` is refused as "no
   branch <name> in <upstream>"; then `build(target, commit, logs)`. `--commit` takes a full hexadecimal object name
   (40 or 64), anything else refused before the adapter starts, since the adapter takes a full one.
6. **The answer.** `BuildStatus` (record: project, repository, branch or none, commit, verdict, detail, jobs) is the
   one implementation; the command only renders it, so a daemon method later renders the same record. Human: the
   commit, the verdict and detail, one line per job with its result, then each job log asked for under its job's
   name. `--json`: one object of those fields, a job's log as text (`Job.logText`). **A log is the forge's text and
   reaches a terminal**: in the human form every control character but newline and tab is written as `\x..`, so a
   log cannot move the cursor, set a title or hide a line; `--json` escapes them anyway.
7. **Refusals and exit codes**, each a named value: unknown project 2 and repository not chosen 64, as `task start`;
   both or neither of `--branch`/`--commit`, a short commit 64; no `builds:` 78; locked vault or missing entry 77;
   adapter not installed, `Unreachable`, `RateLimited` (with its retry-after), `CredentialRefused`, `NoSuchRepository`
   69, each naming its next action; no answer from the adapter at all 70 through `CliErrors`. A build that is
   `queued`, `running` or `unknown` is an answer, exit 0; `failure` and `cancelled` exit 1, so a script can tell.
8. **The token appears nowhere**: it travels only in the socket call (`Target`), never in the adapter's arguments,
   the output, `failures.log` or a refusal's text; `Target.toString` already omits it.
9. **The pages**: `doc/commands.md` (the group table and a `build` table), `doc/build-readers.md` (the command asks the
   same adapter), `doc/running.md` (a short section on asking by hand); a changelog entry.

**Red first:** `BuildStatusCommandTest.namesTheCommitTheVerdictTheJobsAndTheFailedJobsLog`, with a fake
`BuildReader` answering a failed build: no `build` command exists. Then, each seen red: a project without `builds:`
refused with 78 and its sentence; a checkout of a followed project resolves without `-p` and writes no `default`
entry; a log holding `ESC[2J` prints `\x1b[2J`; the token string is in neither stream nor `failures.log`.

## Acceptance

- `porelo build status -p <project> -r <repository> --branch=main` against GitHub names the commit, the verdict, the
  jobs and a failed job's log, for one red and one green Build; `--commit` the same for a commit; `--logs=none` shows
  no log, `--logs=all` every job's; `--json` the same fields.
- A project without `builds:`, `default` among them, is refused with 78 naming why; a locked vault names the unlock;
  the token is found in no output and no log.
- On the VM against the stub (`build-status.feature`, beside `task-builds.feature`, `@slow`): a failed and a passed
  build of a branch, a commit, `--logs=none`, a project without `builds:`, a missing credential entry and a refusal the
  stub is told to give, each with its exit code; the token absent from both streams.

## To be checked

- That `WorkOrigin.followedNaming` writes nothing, read whole, and what it answers for a checkout of the project's
  own repository.
- Whether the `github` adapter accepts an abbreviated commit; if it does, `--commit` may take one and say the full
  name it was read as.
