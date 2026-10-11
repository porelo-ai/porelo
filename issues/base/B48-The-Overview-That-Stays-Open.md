# B48 — The Overview That Stays Open

**Status:** Analyzed.
**Size:** M - the CLI only and no contract change, but it splits the listing, adds a stream reader and needs a terminal
acceptance step.

**What must be true.** A person at a terminal watches their tasks change, from the same answer the interface
reads, without a loop they wrote themselves.

## Why

It is small, and it is the only way a person at a terminal sees what `task status` (its activity and since) and
the waiting reading (Task.screen and Task.lastMessage) went to the trouble of working out.

### What happens today

`sokar task list` prints a table and exits. Somebody who wants to watch their tasks - which is the
normal thing to want while three of them are running - types it again, or wraps it in a loop:

    while true; do clear; sokar task list; sleep 2; done

That is a fleet view written by hand, badly: it redraws whether anything changed or not, it
flickers, it loses the cursor, and it cannot tell the operator *which* line changed. Every person
running Sokar over ssh writes some version of it.

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

1. **One command shows the tasks on this machine and keeps showing them**, updating as they
   change, until the person stops it. It is the same listing `sokar task list` prints - not a
   second rendering that can drift from it.
2. **What it shows comes from the same answer the interface gets.** It consumes `Watch` rather
   than polling the inventory in a loop of its own; two change detectors for one question is how
   the CLI and the graphical view come to disagree about the same machine.
3. **It redraws when something changed, and for nothing else** - with the age as the stated
   exception, recomputed locally from the timestamp rather than fetched.
4. **Stopping it is Ctrl-C, and stopping it is not a failure.** It leaves the terminal as it found
   it: no lost cursor, no colours left on, no scrollback eaten. The reset this product already had
   to learn for attaching applies here too.
5. **It degrades rather than refuses.** Where output is not a terminal, it prints changes as they
   happen instead of drawing over itself, so `| tee` in a session log still produces something
   readable. Where the stream is not available it says so and falls back to the single listing,
   naming what it could not do.
6. **What needs a person is visible without reading every row.** Now that the daemon produces `waiting`, a
   task waiting on somebody is the thing this view exists to surface; a person should not have to
   compare columns to find it.

## Plan

Read in today's code. `TaskListCommand.render(PrintWriter)` (`apps/app-task`) builds its answer through
`TaskInventory` and prints the table in the same method. The daemon's `Watch` (`15-task.varlink`, served by
`TaskMethods` in `daemon`) sends the whole list on every change and leaves the age out. `VarlinkClient.callMore`
(`wire`) streams replies to a consumer; `ShieldSubscribeCommand` (`apps/app-egress`) is a CLI command that already
consumes a stream this way. `SokarPaths.daemonSocket()` names the socket.

Steps, in order:

1. Red first: a test that changes the listing's format and expects the one-shot and the watching output to change
   together. Then split `render` into a `TaskListing` that takes the tasks, the unrecognized names and the current
   time, and is called by both.
2. A reader from a `Watch` reply back to `TaskInventory.Task`, beside the daemon's own mapping and tested as a round
   trip, so that the view renders the same record the listing does.
3. The watching form (a flag on `task list`, pending the question below): connect to `daemonSocket()`, call `Watch`
   with `more`, and let each reply replace the held list. A local clock redraws only the age column, once a minute,
   from `since`.
4. At a terminal: draw in the alternate screen with the cursor hidden; redraw only rows whose fields other than the
   age differ; restore screen, cursor and colours on exit, on Ctrl-C and on a failure, with the same reset that
   attaching uses. Exit 0 on Ctrl-C. Waiting tasks sort first and are marked. A unit test with a recording terminal
   asserts which rows were written for one change, and that a minute with no change writes only ages.
5. Not at a terminal: one line per change with the time, the task and what changed; a test finds no escape
   sequence.
6. No daemon: say so, print the one-shot listing and exit, naming what could not be done.
7. Acceptance, in the terminal kit: start the view, stop a task from a second terminal, see its row change; Ctrl-C,
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
- Piped to a file, the output is readable line-by-line history rather than escape sequences. Seen to fail: a
  test of the piped output finds an escape sequence.
- The rendering is the same code that prints the one-shot listing, proven by a test that changes
  the listing's format and sees both change. Seen to fail: that test, when only one of the two changes.

## Open questions

- **Is it a flag or a verb?** (the operator's) `sokar task list --watch` reads as the listing that keeps going, and
  the house already has `sokar shield watch` as a verb. A flag keeps the two renderings obviously the same thing; a
  verb is easier to give its own options later. Decide once - the two will not be reconciled afterwards.
- **Does it belong to one project or to the machine?** (the operator's) A filter in the watching form and not in the
  listing breaks the "same listing" promise, so a filter would be added to both.
- **Does the same view show the clearance questions?** (the operator's) `Prompts` carries questions that expire, and
  a person watching their tasks is the person who should see one; or it makes this view two things at once.

## To be checked

- **Does it need the daemon?** (Agent Core's) `sokar task list` builds its answer in this process and works where
  nothing runs but podman. Consuming `Watch` makes the watching form need a daemon, which an operator meets as an
  error message. The plan states it plainly and falls back to one listing; the other choice is polling its own
  inventory, as the second change detector point 2 refuses.
