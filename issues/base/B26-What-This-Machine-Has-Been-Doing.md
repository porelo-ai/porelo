# B26 — What This Machine Has Been Doing

**Status:** Analyzed.
**Size:** L - a new durable record written from many places, a method and a command over it, and two caps to measure.

**What must be true.** An operator can ask a machine what it has done — which tasks ran, what
reached the gate, what was refused and what failed — and get an answer for longer than the tasks
themselves existed, without anything a container wrote being read.

## Why

The account's durable event log records some host-side events; the full event list, cursor, live
streaming and retention caps required here are not built.

### What exists, so that this is not built over something already there

Each task's runtime state directory can hold these files, and they **are** host-side — the gate, the broker,
the relay, the resolver and the clearance watcher all run on the node, not in the container:

| file | written by |
|---|---|
| `gate.log` | the git gate serving that task |
| `vault.log` | the credential broker |
| `relay.log` | the port relay |
| `dnsmasq.log` | the resolver |
| `clearance.log` | the clearance watcher |
| `workspace.log` | the clone |
| `events.jsonl` | blocked connections, from the NFLOG reader |

`Logs(task)` and `Tail(task, log)` put those on the wire already, and `Logs` deliberately asks
rather than assumes, because which files exist depends on what the task started.

**What exists and what is missing.**

- **The CLI reads task logs.** `porelo task logs` lists and reads them, and can follow one.
- **The event log is account-wide and durable.** `porelo events` and the daemon's `Events(since)` read it.
- **The runtime logs do not survive.** A task's runtime state directory is under `$XDG_RUNTIME_DIR`, cleared when
  the user's last session ends. A machine's history was deleted by a reboot — measured twice, once as tasks
  listing no project and once as a task that could never be resumed.

### Why aggregating the seven files is the wrong answer

Seven files, seven formats: dnsmasq writes its own, each helper writes its own. Merging them by
time means parsing all seven, and **a parser that silently mis-orders entries is worse than no
view** — it produces a plausible story of what happened, which is the failure this project keeps
writing down. It would also be a read per file per task on every refresh, which is the listing
cost `since` and `behindMeasured` both exist to avoid.

The per-task logs stay. They are the detail. The event log is the spine, with gaps listed in the plan below.

## The shape

### Notes

**This extends the event log.** Every moment worth recording needs a call added at the
place it happens, and each one is a decision about what is worth saying. That is the cost, and it
is the reason to argue the event list before writing any of it.

**A node is an OS user with a `porelod`,** so this is per user rather than per machine, and two
users on one host have two journals. Confirmed with the interface, whose machine list is keyed the
same way, so the two map one to one. **If this ever becomes host-wide it must be said loudly** - it
would be the single place where the two sides' meanings of "machine" diverge.

**The interface will want it, but the interface is not the reason.** An operator asking "what
happened here last night" can read the event log, but the full event list below is not recorded.

### Decided

- **Retention: both caps, whichever bites first.** A size cap alone lets one loud day push out a
  month of history on a busy machine; an age cap alone bounds nothing on one. **The journal records
  its own trimming**, because history that shortens silently is history nobody can trust — "there
  is nothing from before the 3rd" and "nothing happened before the 3rd" must not look alike.
- **Reading: `Events(after: ?cursor)`, every event carrying its own cursor.** One method for
  three jobs: no cursor is history, the last cursor is the resume, and `more` follows. The
  interface argued it better than this file first did — **a machine reached over ssh is a forward
  that drops**, and every drop is a hole in a pure stream that comes back looking calm. The
  failure a journal must not have is the one where nothing happened for ten minutes and nothing
  says whether that is true.
- **An event names the task, never a log file.** The journal outlives the state directory, so a
  stored path is a promise it cannot keep. A client wanting detail asks `Logs(task)`, which already
  answers what still exists - so the journey from spine to detail arrives at "this task has no logs
  any more" rather than at a dead path. Two conditions on that field, both from the interface's own
  scars: **it is absent for an event that belongs to the machine rather than a task**, and absence
  must not render as an unknown task; and where a kind is cheap - gate, broker, clearance - it is
  carried beside the name, so the spine can say which log without naming a file.
- **`porelo task logs` is built now, separately.** It needs nothing from this requirement.

## Plan

Read in the code on 2026-11-04.

**What is there now.** `porelo task logs` is built
(`apps/app-task/src/main/java/org/fuin/porelo/app/TaskLogsCommand.java`). A task's runtime state directory is still
`xdg.runtime()/<container>` (`TaskPaths`), so those logs still die at a reboot. Account-wide and durable, under
`xdg.state()` (`PoreloPaths`): `events.jsonl`, `failures.log`,
`stops.log` (each `task stop --all` and `panic`, with who and when), `grants.log` (whom a task acted as, [B31](README.md#b31)), the
clearance journals, and the message record (`MessageRecord`). `EventLog` is the spine: it records gate arrivals,
approvals and rejections, clearance decisions, hand-ins, credentials given at start and stops of all tasks.
`project-applied` is declared as a kind but has no writer. Individual task starts, stops and removals, image builds,
hook repairs and unexpected command failures are not recorded there. Its `detail` field accepts free text rather
than enforcing names and outcomes.

**One log, not two.** `EventLog` is built in `apps/app-base/src/main/java/org/fuin/porelo/app/EventLog.java`, at
`$XDG_STATE_HOME/porelo/events.jsonl`, with the daemon's `Events(since)` and `porelo events --since`.
It is hash-chained, each hash anchored in the user's systemd journal, and appended under a file lock.
This journal is that file. What [B26](README.md#b26) adds is the missing events, the cursor, streaming and the caps.

Steps:

1. Red first, in `apps/app-base`: `EventLog` appended past a cap keeps within it and writes a trimming event; made
   unwritable, `EventLog.record` returns and the caller carries on; an event given a value that looks like a
   credential, a prompt or agent output is refused by the writer's shape (names and outcomes only, no free text field).
2. Extend `EventLog.append(kind, project, task, detail)`: bound each JSON line under `PIPE_BUF` with `O_APPEND`,
   retain the file lock, add a monotonic cursor; both caps (size and age) checked on append, trimming recorded.
3. The writers, one line each: `TaskLaunch` (start), `TaskControl` (resume, stop, remove), the image build
   (`Preparation`), the hooks'
   repair (`SetupCommand`, and where `TaskLaunch` repairs them), the gate (a push arriving in `GitHttpServer`,
   `GateApproveCommand`, `GateRejectCommand` and the daemon's same calls), a clearance decision (`RunningClearance`
   and `ShieldWatchCommand`), a
   credential brokered for a task (the broker, once per credential per task rather than per request), and
   `failures.log`'s writer.
4. Add `after: ?string` to `Events` in `daemon/src/main/resources/varlink/org.fuin.porelo.Tasks1/55-events.varlink`,
   keeping `since`, and streaming with `more`; add `--follow` to `porelo events`, reading the same file through
   the same reader, so both show the same order. `InterfaceDescriptionTest` holds them.
5. An acceptance scenario: events recorded, the runtime directory cleared and the task removed, the journal still
   answers - without a reboot, which the shared machine must not do.
6. `doc/commands.md` and `doc/how-it-works.md`: what is recorded and what never is.

## Acceptance

- Events are recorded for at least: a task started, stopped, or removed; an image built; hook
  registration repaired; a push arriving at the gate, approved or rejected; a clearance decision;
  a credential brokered for a task; a command failing unexpectedly.
- **The record survives a reboot and the removal of the task it refers to.** That is the property;
  a journal in the runtime directory would be this requirement's own failure.
- Each event says when, what happened, and which task or project it concerns — enough to be read
  in order without joining it against anything else.
- **No argument, credential, prompt, or agent output is ever written to it.** Names and outcomes
  only. The same rule `failures.log` follows, for the same reason: a log is where a credential
  must not end up.
- It is readable at the terminal and over the socket, and both show the same events in the same
  order.
- Following it live never requires polling a file from a client.
- It cannot grow without bound on a machine nobody administers.
- Writing an event never fails a command. A journal that cannot be written loses an entry; it does
  not stop a task.
- **Seen to fail:** a test that records events, reboots or clears the runtime directory and removes
  the task, then reads the journal, goes red when the events are gone; a test that runs a command
  carrying a credential, a prompt and agent output goes red when any of them appears in the
  journal; a test that makes the journal unwritable goes red when the command fails; a test that
  writes past either cap goes red when the journal grows beyond it or trims without recording that
  it did; a test comparing the terminal's and the socket's reading goes red when they differ.

## To be checked

- **Concurrent appends**. The daemon, the CLI and several helpers write to one file under a file lock.
  Cursor assignment and trimming must share that lock; line length is not bounded today.
- **Whether an event can be tied to its task without widening what an event holds**. A security
  review asked for correlation and proposed carrying destination address, port and process. The journal carries
  names and outcomes; what is open is whether a task id alone is enough.
- **What the two caps actually are**. Decided that there are two; the numbers should come from what a
  busy machine really writes rather than from a round figure.
- **Whether trimming and a hash chain can live in one file**. `EventLog` chains and anchors every line;
  [B26](README.md#b26) wants the file bounded. Trimming a chain needs the first kept line's predecessor hash kept as an anchor, or the
  chain restarts at each trim and says so.
