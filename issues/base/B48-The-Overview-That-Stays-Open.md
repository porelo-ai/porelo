# B48 — The Overview That Stays Open

```json
{
  "$schema": "issue-1.json",
  "id": "B48",
  "title": "The Overview That Stays Open",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 35,
  "blockedBy": [],
  "covers": "`task list --watch` (machine-wide, `--project` on both forms) on the daemon's `Watch` stream through the shared listing code, a hint line for waiting clearance questions, ages redrawn locally, the terminal reset on Ctrl-C."
}
```

**Why this size.** the CLI only and no contract change, but it splits the listing, adds a stream reader and needs a terminal
acceptance step.

**What must be true.** A person at a terminal watches their tasks change, from the same answer the interface
reads, without a loop they wrote themselves.

## Why

It is small, and it is the only way a person at a terminal sees what `task status` (its activity and since) and
the waiting reading (Task.screen and Task.lastMessage) went to the trouble of working out.

### What happens today

`porelo task list` prints a table and exits. Somebody who wants to watch their tasks - which is the
normal thing to want while three of them are running - types it again, or wraps it in a loop:

    while true; do clear; porelo task list; sleep 2; done

That is a fleet view written by hand, badly: it redraws whether anything changed or not, it
flickers, it loses the cursor, and it cannot tell the operator *which* line changed. Every person
running Porelo over ssh writes some version of it.

**The hard half is already built and is not reachable from a terminal.** The daemon serves
`Watch`, and it is careful in exactly the way a hand-written loop is not:

```java
// Only on change - and "change" cannot include the age. The runtime's state is a
// phrase carrying one: 'Up 3 seconds' becomes 'Up 4 seconds' a second later, so
// comparing the whole answer made this fire every second and told a fleet view to
// redraw because a clock had moved.
```

The wire client can consume it - `VarlinkClient.callMore` exists and hands each reply to a
consumer - and nothing in the CLI calls it. So the one client of `Watch` is the graphical
interface, and the terminal, which is where a person is when they are logged into the machine at
all, gets the snapshot.

### The thing that has to be decided rather than copied

`Watch` deliberately does **not** report an age-only change, and it is right not to. A view that
redrew because a clock moved is the failure that comment records, measured against a real task.

But *"idle for forty minutes"* is the number a person watching is actually watching, and it
changes once a minute by definition. So a watching terminal view cannot simply redraw what
arrives: it redraws the age from the timestamp it already holds - the contract carries that timestamp
precisely so arithmetic replaces parsing - and redraws the rest only when the stream says
something changed. One source for what is true, a local clock for how long it has been true. Get
this wrong in the other direction and the view either freezes its ages or asks the daemon for a
list every second, and the second is what `Watch`'s design exists to prevent.

## The shape

1. **`porelo task list --watch` shows the tasks on this machine and keeps showing them**, updating
   as they change, until the person stops it. It is a flag, not a verb: the same listing `porelo task
   list` prints - not a second rendering that can drift from it.
2. **It covers the whole machine, and `--project <name>` narrows it** - on the listing and on the
   watching form alike, so the two never show different sets of tasks.
3. **What it shows comes from the same answer the interface gets.** It consumes `Watch` rather
   than polling the inventory in a loop of its own; two change detectors for one question is how
   the CLI and the graphical view come to disagree about the same machine.
4. **It redraws when something changed, and for nothing else** - with the age as the stated
   exception, recomputed locally from the timestamp rather than fetched.
5. **Stopping it is Ctrl-C, and stopping it is not a failure.** It leaves the terminal as it found
   it: no lost cursor, no colours left on, no scrollback eaten. The reset this product already had
   to learn for attaching applies here too.
6. **It degrades rather than refuses.** Where output is not a terminal, it prints changes as they
   happen instead of drawing over itself, so `| tee` in a session log still produces something
   readable. Where the stream is not available it says so and falls back to the single listing,
   naming what it could not do.
7. **What needs a person is visible without reading every row.** Now that the daemon produces `waiting`, a
   task waiting on somebody is the thing this view exists to surface; a person should not have to
   compare columns to find it.
8. **A clearance question waiting is a hint line, not a prompt.** The view says how many questions
   are waiting and for which tasks; they are answered where they are answered today, and the view
   stays one thing.

## Plan

Read in today's code. `TaskListCommand.render(PrintWriter)` (`apps/app-task`) builds its answer through
`TaskInventory` and prints the table in the same method. The daemon's `Watch` (`15-task.varlink`, served by
`TaskMethods` in `daemon`) sends the whole list on every change and leaves the age out. `VarlinkClient.callMore`
(`wire`) streams replies to a consumer; `ShieldSubscribeCommand` (`apps/app-egress`) is a CLI command that already
consumes a stream this way. `PoreloPaths.daemonSocket()` names the socket.

Steps, in order:

1. Red first: a test that changes the listing's format and expects the one-shot and the watching output to change
   together. Then split `render` into a `TaskListing` that takes the tasks, the unrecognized names and the current
   time, and is called by both.
2. A reader from a `Watch` reply back to `TaskInventory.Task`, beside the daemon's own mapping and tested as a round
   trip, so that the view renders the same record the listing does.
3. `--project <name>` on `task list`, filtering the tasks before `TaskListing` sees them, so the one-shot listing and
   the watching form take the same filter; a test lists a machine with two projects both ways.
4. The watching form, `--watch` on `task list`: connect to `daemonSocket()`, call `Watch` with `more`, and let each
   reply replace the held list. A local clock redraws only the age column, once a minute, from `since`.
5. Beside `Watch`, the view subscribes to `Prompts` and keeps the open questions (dropping one when its verdict or
   its expiry arrives). While any is open, one hint line above the table says how many wait and for which tasks,
   and where they are answered; the view offers no way to answer them. Under `--project` it counts only that
   project's tasks. A test sees the line appear on a question and go on its verdict.
6. At a terminal: draw in the alternate screen with the cursor hidden; redraw only rows whose fields other than the
   age differ; restore screen, cursor and colours on exit, on Ctrl-C and on a failure, with the same reset that
   attaching uses. Exit 0 on Ctrl-C. Waiting tasks sort first and are marked. A unit test with a recording terminal
   asserts which rows were written for one change, and that a minute with no change writes only ages.
7. Not at a terminal: one line per change with the time, the task and what changed; a test finds no escape
   sequence.
8. No daemon: say so, print the one-shot listing and exit, naming what could not be done.
9. Acceptance, in the terminal kit: start the view, stop a task from a second terminal, see its row change; Ctrl-C,
   then `stty -a` and the cursor show the terminal as it was. The step goes red when the reset is removed.

## Acceptance

- The command is started against a machine with tasks on it, a task changes state, and the view
  shows the change without being asked and without redrawing rows that did not change. Seen to fail: a test
  that changes one task's state sees no redraw, or sees every row redrawn.
- Nothing changes for a minute and the view redraws only the ages. Asserted, because the
  alternative passes a casual look and costs a redraw per second. Seen to fail: the assertion goes red when
  the view redraws a row other than its age, or asks the daemon for a list, while nothing changed.
- Ctrl-C returns to a shell whose cursor, echo and colours are as they were, asserted by the
  acceptance kit rather than by reading the code - this is the failure mode that was already met
  once when attaching, and it is only visible at a real terminal. Seen to fail: the acceptance scenario goes
  red when the terminal reset on exit is removed.
- `--project <name>` shows the same tasks in the listing and in the watching form, and the watching form without
  it shows every task on the machine. Seen to fail: a test with two projects finds a task of the other project in
  either form, or a task missing from the unfiltered view.
- A waiting clearance question shows as one hint line naming its task, and the line goes when the question is
  answered or expires. Seen to fail: a test that sends a question and its verdict through `Prompts` sees no line,
  or a line that stays.
- Piped to a file, the output is readable line-by-line history rather than escape sequences. Seen to fail: a
  test of the piped output finds an escape sequence.
- The rendering is the same code that prints the one-shot listing, proven by a test that changes
  the listing's format and sees both change. Seen to fail: that test, when only one of the two changes.

## Built (2026-11-12)

- **One listing, two forms.** `TaskListing` (`apps/app-task`) holds the table's text: header, a row in its parts
  (everything but the age, and the age cell with its column), the line said when there is no task, and the notes
  under the table. `TaskListCommand.render` prints it once from this process's `TaskInventory`; the watching view
  draws the same rows with a two-character marker column in front. `TaskListWatchTest` runs `task list` and
  `task list --watch` against one machine and finds the same header and rows in both.
- **The state column keeps only the state's first word** (`Up`, or `Exited (<code>)` as before), the reduction the
  daemon's `Watch` already compares by: the age column says how long, and a view sent a list only when something
  other than an age changed would otherwise show `Up 3 seconds` for an hour. This changes what the one-shot listing
  prints; no acceptance scenario read the age out of the state column.
- **`TaskRows`** reads a `Watch` row back into a `TaskInventory.Task`. `TaskRowsTest` sends a task with every field it
  reads set through the wire's JSON and gets the same row and the same rendering back. Not read back, since no
  listing shows them: how the agent's run ended (`agentEnded`), the files handed in, the builds of its pushes and the
  file it handed back. The reader lives in `apps/app-task` beside its one caller, not in `app-model` beside `asMap`.
- **`--project <name>`** on `task list` filters the tasks before the listing sees them, in both forms; without it
  both show every task. Containers named like tasks that Porelo holds no id for are still noted under a project's
  listing, since no project can be read for them.
- **`--watch`** connects to the daemon's socket, calls `Watch` with `more` and lets each reply replace the held list
  (`TaskWatch`); a second connection follows `Prompts`. A thread works the ages out again once a minute from each
  task's `since`; nothing asks the daemon for a list.
- **Clearance questions** are kept from `Prompts` until their verdict (`allow`, `deny`, `timeout`) or their deadline;
  while any is open, the first line says how many wait, for which tasks, and that they are answered where clearance
  is asked, not in this view. Under `--project` only that project's tasks count. A `missed` count on the stream adds
  that questions may be waiting unseen; a `Prompts` that fails puts that on the line instead.
- **At a terminal** the view draws on the alternate screen with the cursor hidden and line wrapping off, keeps the
  first line for the hint so a question moves no row, writes only rows whose text other than the age changed, and
  for an age alone writes just the age cell. Tasks waiting for somebody (a question seen asked, a screen its agent
  declares a wait on, or its work at the gate) sort first and carry `!`. On leaving it turns colours off and wrapping
  on, then writes the reset an attach ends with (`TerminalAttach.RESETS`: main screen, cursor shown, mouse and other
  modes off). Ctrl-C runs that from a shutdown hook, which then halts with 0; the normal end runs it in `finally`. The
  view changes no `stty` setting, so it saves and restores none.
- **Not at a terminal** it prints the listing once, then one line per change: the time, the task and what changed
  (`state Up -> Exited (1)`, `helpers 2 -> 0`, waiting or not), a task listed or gone, the hint line when it changes.
  No escape sequence.
- **Without a daemon** it says that porelod does not answer at the socket and the list cannot be watched, prints the
  listing once and exits 69 (EX_UNAVAILABLE). A daemon that refuses `Watch` before its first reply is answered the
  same way; one that stops mid-way gets the terminal given back, a line saying what was shown is no longer current,
  and 69.
- **Acceptance**, `acceptance/suite/.../task/task-list-watch.feature` (`@slow`): start a task, open the view in a
  terminal, stop the task from a script, see `Exited (`; Ctrl-C; `stty -a` compared with the one taken before, and the
  new kit step "the terminal is back on its main screen with its cursor shown", which fails when the alternate screen
  was never left or the cursor left hidden (`TerminalStepsTest` holds that, red when the check is removed). The kit
  gained the key `Ctrl+C`. The scenario was written, not run: it needs the VM.

Not built: the view asks neither the terminal's height nor its width. A list longer than the screen is not scrolled,
and with wrapping off a line wider than the screen, such as the note on containers Porelo holds no id for, is cut at
its edge. Whether the watching form should need the daemon is settled as planned: it does, and without one it says
so and lists once.
