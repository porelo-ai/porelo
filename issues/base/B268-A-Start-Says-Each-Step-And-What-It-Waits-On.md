# B268 — A Start Says Each Step And What It Waits On

**Status:** Backlog. Built together with [B265](README.md#b265) and [B267](README.md#b267); its transport part is
porelo-message-matrix's, its rendering porelo-frontend's (see *To be checked*).
**Size:** M - one contract change (`Tasks1` grows), a step model through `app-model`, `app-task`, `app-messaging` and
the daemon, `doc/`, and an acceptance step.

**What must be true.** A task's start reports each of its steps in plain words as it takes it, says what it waits
on while it waits, and on a failure names the cause with the last error line of the dependency that failed. A start
waiting on the room's homeserver says so, and when the homeserver fails, its last error line is in the refusal. The
daemon carries the same steps, so the interface only shows them and writes no text of its own for them.

## Why

Seen in the operator's test on a rented host on 2026-10-23: while the first task started, the interface showed
"Running." with the last output line "snippet…". Nothing said that the start waited on the room's homeserver, which
had already failed five times. After the transport's three minutes the start ended with "Nothing ran, nothing was
created", and the cause was found only in the host's journal. The operator decided that the interface shows step
texts and writes none: a start's steps come from porelo, where the steps are defined.

Read in today's code:

- **A start has no steps, only lines.** `TaskLaunch` prints column-aligned lines to `out` and `err`; the CLI shows
  them, and `TaskMethods.start` merges both into one sink that becomes `line` replies or `output`. There is no step
  type, no start or end of one, and no "waiting on" anywhere.
- **The wait the test saw is the conversation's enrollment** (`TaskConversations.enroll`, called from `TaskLaunch`
  before anything exists). It runs the transport's `setup` and `enroll` through `ProcessCommandRunner`, and prints
  nothing before it. So the last line the person saw was the image's `snippet` line, three minutes earlier.
- **The three minutes are the Matrix transport's own** (`LocalHomeserver.START_TIMEOUT`, 180 s polling the
  homeserver). On the timeout it says only "did not answer within 180 s": it checks `systemctl`'s exit code and reads
  neither the unit's journal nor the container's log, so the failing homeserver's own words are lost.
- **Porelo then quotes the transport's whole standard error**, wraps it, prints "nothing was created" and returns 69.
  The interface writes its own sentence for 69 ("Nothing ran, and nothing was created").
- **When `ProcessCommandRunner`'s ten-minute bound ends a run, everything it read is dropped**, and the start ends
  70 with "Timed out after 600 s".
- **`phase` in `List` and `Watch` is always ""** (`TaskInventory`: "Nothing records a phase yet"), and `Start`'s
  `now` is read nowhere. A window opened during a start sees nothing.

## Plan

1. **A step crosses varlink as a record**, added to `15-task.varlink` (an added type and optional fields keep
   `Tasks1`):

       type StartStep (
         step: string,      # stable: SOURCE CHECKS CREDENTIALS WORKSPACE CONVERSATION IMAGE CONTAINER HELPERS AGENT
         state: string,     # STARTED WAITING DONE FAILED
         text: string,      # English words, one sentence: "Joining the project's conversation on matrix"
         waitingOn: string, # what it waits on, "" when it waits on nothing outside Porelo
         cause: string      # FAILED only: the dependency's last error line, "" when it gave none
       )

   `Start`'s reply gains `step: ?StartStep` on a streamed reply and `steps: ?[]StartStep` on the final one when not
   streamed. The lines stay as they are, so a client that knows no steps loses nothing.
2. **`StartSteps`** in `app-model`: the one place the steps and their words are defined. `TaskLaunch` takes it beside
   `out` and `err` and reports each step as it begins, waits and ends. The CLI renders a step as one line; the daemon
   sends it as `step`. The interface writes no text of its own for a step.
3. **`phase` comes from the step**: each step is written to the task's state directory as it changes, and
   `TaskInventory` reads it, so `List` and `Watch` show "Waiting on the room's homeserver" while it waits, for a
   `now` start and a second window alike.
4. **What a dependency waits on is the dependency's to say**: `describe` gains an optional `waitsOn` (for Matrix, "the
   room's homeserver"); Porelo shows "the matrix transport" when it gives none. `doc/transports.md` describes it.
5. **The cause is the dependency's last error line**: `TransportLifecycle.run` keeps the last non-empty line of the
   transport's standard error, at most 300 characters with control characters removed, as the step's `cause`.
   `ProcessCommandRunner`'s timeout keeps what it read, so a run ended by the bound has a last line too. The image
   build's step takes podman's last error line the same way.
6. **`doc/`**: `doc/commands.md` lists the steps and their states under `task`; `doc/transports.md` the `waitsOn` field and that a
   transport's last error line is shown.
7. **Tests, red first:**
   - a stub transport that sleeps and then fails with two error lines: the streamed `Start` carries `CONVERSATION`
     `WAITING` with its `waitingOn`, then `FAILED` with the second line as `cause`; today there is no step;
   - while it waits, `List` shows the waiting step's text as `phase`; today "";
   - a run past the runner's bound keeps its last line; today it is dropped;
   - the CLI's lines and the daemon's steps come from the same `StartSteps` calls (one fake launch, both renderings);
   - an acceptance step: a start with the stub transport made to wait names the wait in `porelo task start`'s output.
8. A changelog line under *Added*.

- **Build estimate=240**, like porelo [B258](README.md#b258) (M, a contract file, docs and tests through several
  modules), plus the acceptance step.

## Acceptance

- A start waiting on the room's homeserver says so while it waits, in the CLI, in `Start`'s stream and in `phase`.
- A failed homeserver's last error line is the refusal's cause, in the CLI and in the step's `cause`; nothing has to be
  read from the host's journal.
- The interface shows the steps' texts and has none of its own for them.

## To be checked

- **Agreed with porelo-message-matrix:** `describe`'s `waitsOn`, and that its `setup` ends a homeserver timeout with the
  unit's last journal line or the container's last log line on standard error (today it reads neither). That is their
  issue; Porelo's part works without it, with a less telling cause.
- **Agreed with porelo-frontend:** the `StartStep` record, and that the interface renders `step` and drops its own
  sentence for 69 where steps came.
- Whether a homeserver's last log line can hold a secret (an access token in a logged URL); if it can, the transport
  removes it before printing, and Porelo's 300-character cut is no protection.
