# Writing a build reader

A build reader tells Porelo what a forge built: where a branch points, and what the build of a commit did. It is an
executable of its own, installed on the host, which Porelo starts and asks over a socket; it never runs inside a
task. What a task sees of it is in [being told what the build did](running.md#being-told-what-the-build-of-a-push-did).
This page is for whoever writes one for a forge Porelo does not read yet.

[`porelo build status`](commands.md#build) asks the same installed reader by hand, for a branch or a full commit.
Its repository, API address and credential entry come from the project's `builds:` declaration. Each query starts
the reader with a socket in a new owner-only directory under the account's runtime directory and removes that
directory when it finishes, including after a refusal. The token travels only in the socket call.
Human status output groups jobs under runs by trigger and id. A failed job's named failed tests replace its log;
its HTTPS page is shown only on the repository upstream's host. Jobs without an unambiguous matching run are
listed apart, including when the API clears an unknown run reference. JSON carries the metadata; a failed job with named tests has an empty `log` field.

## What it is built on

`porelo-build-api` on Central, and `porelo-wire` beside it: nothing else of Porelo's. A reader implements one
interface, `BuildReader`, and its main method is one line:

```java
public static void main(String[] arguments) {
    BuildMain.run(new MyForgeReader(), arguments);
}
```

`BuildMain` answers `describe` with the forge's name and the protocol version, and `serve <socket>` with the
varlink interface `org.fuin.porelo.Build2`. Porelo starts a reader as `--limits <line> serve <socket>`, handing it
the [time factors](limits.md) it holds; `BuildMain` takes the line before the mode, so a reader built on a
`BuildMain` that does not take it fails at its start. Porelo refuses a reader whose protocol version it does not
know, and one that reads another forge than the project names, before it asks it anything. If the reader does not
serve this interface, the refusal names its executable, the build interfaces it serves and the interface Porelo
requires.

## What it answers

- **`forge()`**: the name a project file gives under `builds.forge`, and the name the executable is installed
  under: `/usr/libexec/porelo/builds/<forge>`, or `$XDG_DATA_HOME/porelo/builds/<forge>` for one account.
- **`head(target, branch)`**: the commit a branch points at, "" when the forge has no such branch.
- **`build(target, commit, logs)`**: what the build of one commit did, across everything the forge ran for it.
- **`openPullRequest(target, branch, title)`**, optional: opens a pull request from the branch into the repository's
  default branch, or answers the one already open from it, so asking twice opens one. Without a title it takes the
  subject of the branch's newest commit. The task's builds helper asks it once, at an `online` task's first push, as
  the forge account whose token `builds:` names - never the person's, who approves it. On a host, whose builds
  credential is the person's own token, it asks with the task's own forge token instead, the one the host made for
  the task's account at its start, and with no other: without it no pull request is opened, the builds are still
  told, and `task status` says why. A reader that leaves the
  default in place opens none: Porelo then says so in `task status`, and the task runs as before.

`target` carries the repository as the project file names it (the reader takes it apart: only it knows how its
forge spells a repository), the API's address ("" for the forge's own) and the token. **A reader keeps no secret**:
Porelo reads the token from the vault and hands it over the owner-only socket in each call. A reader never writes
it anywhere, and never sends it to any host but the forge's API - a log behind a redirect to storage is fetched
without it.

## Answering a pull request's review

The same executable can answer Porelo about a pull request's review, over a second varlink interface,
`org.fuin.porelo.Review1`, on the same socket. A reader that also implements `ReviewReader` serves it beside
`Build2` through the same `BuildMain`; one that does not serves `Build2` alone, as before. Porelo refuses an adapter
that serves no review, or another review version, before it asks it anything. The description,
`org.fuin.porelo.Review1.varlink` in `porelo-build-api`, is the contract; in short:

- **`pullRequest(access, url)`**: a pull request's facts - the repository its head is read from, the head reviewed,
  the base, the merge base, its state, whether the forge shows it approved at the head, and the merge commit and the
  branch it went onto exactly when merged, and when the forge merged or closed it (an RFC 3339 time in UTC) exactly
  when merged or closed, and the forge account the token stands for (its id and login), absent only when the forge
  does not say.
- **`pullRequestOf(access, upstream, branch)`**: the open pull request from a branch, or none; it opens none.
- **`comments(access, url, cursor)`**: one page of the whole conversation, one [review profile 2](review-profile.md)
  document of kind `page` that states its own completeness, at most 100 results and 1 MiB, and the cursor of the
  next page.
- **`post(access, url, document)`**: writes one released result, a profile 2 submission of exactly one result, and
  answers the thread it answered or the id of the new thread's root, the forge's native id in decimal.

`access` carries the forge's REST base URL ("" for its public one) and the token lent for that call alone. Nothing
of the forge's own format crosses, and no method reaches a verdict: `approved` is what the forge shows, read only.
Porelo hands every document on as bytes and parses none here. The refusals are `RateLimited`, `CredentialRefused`,
`NoSuchPullRequest`, `NoSuchThread`, `Unreachable`, `NotInProfile` (a general or file comment, or anything profile 2
cannot carry, naming the field) and `WriteOutcomeUnknown`: a post that was sent with no answer saying whether it was
written, which Porelo never sends again. An adapter never logs the token and never puts it in an error.

Porelo holds what an adapter answers to profile 2's shapes before it uses it, and refuses anything else as the
adapter's fault:

- **Addresses:** a repository is `https://<host>[:<port>]/` and 2 to 8 segments of `[A-Za-z0-9._~-]{1,100}`, none
  of them `.` or `..`; a pull request is a repository's address and 1 to 3 segments more, the last all digits.
- **Bounds:** a page is UTF-8 of at most 1 MiB, a cursor at most 256 characters, a thread's id 1 to 64 digits, a
  field `NotInProfile` names at most 128 characters, and the branch a pull request merged onto a name git accepts;
  none of them holds a control character. A parameter answered as `null` is absent.
- **Messages:** what an adapter says reaches a message with the lent token replaced by `[token]` and cut to 512
  characters.
- **A broken answer:** a frame that is no varlink answer, or a connection that breaks, ends the adapter, and the
  next call starts it again. During a post either is `WriteOutcomeUnknown`; a post refused before it is sent - a
  submission over 1 MiB, or an adapter that will not start again - is safe to send again.

## The verdict, the jobs and their logs

- **The verdict** is one of `queued`, `running`, `success`, `failure`, `cancelled`, `unknown`, across every run of
  the commit: `failure` as soon as one failed, `running` or `queued` while one is not finished, otherwise
  `cancelled` or `success`. `unknown` says why in its detail: no build of the commit yet, for one.
- **A job** is the smallest unit the forge reports its own result and its own log for: on GitHub and GitLab a job,
  on Bitbucket a step, on Jenkins a stage with a log of its own or else the whole build. Its name is
  `<workflow or pipeline> / <job>`, spelled as the forge spells it; its result is what became of it, in lower case,
  or its state while it is not finished.
- **Which jobs:** every job the forge reports, once the verdict is `failure` or final; none while it is queued or
  running, so a running build costs one question per round and no more.
- **Which logs:** `logs` is `none`, downloading no logs while still listing the jobs, `failure`, each failed job's,
  or `all`, every job's once the verdict is final; absent, `failure`. A project file's `builds.logs` accepts only
  `failure` or `all`, since a task following its pushes needs a failed job's log. A log is
  the job's **plain text**, its last 64 KiB: a reader whose forge sends an archive, or splits a job into files per
  step, unpacks it and joins the steps in their order, one header line each, before it takes the end. At most 50
  jobs, and 2 MiB of logs in all: past 32 logs of 64 KiB, each keeps an equal share of its end, so the answer fits
  one varlink message.

## When the forge will not answer

Not a verdict but a refusal, each its own error, so Porelo tells them apart and so does the task:

| Refusal | When | What Porelo does |
|---|---|---|
| `RateLimited` | the forge's limit is used up; `retryAfter` says for how long | asks again after it |
| `CredentialRefused` | the forge does not take the token, or it lacks the scope | asks again in 10 minutes |
| `NoSuchRepository` | the forge shows the token no such repository | asks again in 10 minutes |
| `Unreachable` | the forge did not answer, or answered something that is not an answer | asks again next round |

A reader that cannot be started, or fails, is a fault of the installation, and Porelo says so in the task's
`buildProblem`.
