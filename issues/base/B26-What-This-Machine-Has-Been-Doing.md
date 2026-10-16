# B26 — What This Machine Has Been Doing

**Status:** Analyzed.
**Size:** L - a new durable record written from many places, a method and a command over it, and two caps to measure.

**What must be true.** An operator can ask a machine what it has done — which tasks ran, what
reached the gate, what was refused and what failed — and get an answer for longer than the tasks
themselves existed, without anything a container wrote being read.

## Why

Every host-side log Porelo keeps belongs to one task, lives on tmpfs, and is gone when that task or
that session is.

### What exists, so that this is not built over something already there

Each task's state directory holds seven files, and they **are** host-side — the gate, the broker,
the relay, the resolver and the clearance watcher all run on the node, not in the container:

| file | written by |
|---|---|
| `gate.log` | the git gate serving that task |
| `vault.log` | the credential broker |
| `relay.log` | the port relay |
| `dnsmasq.log` | the resolver |
| `clearance.log` | clearance decisions |
| `workspace.log` | the clone |
| `events.jsonl` | blocked connections, from the NFLOG reader |

`Logs(task)` and `Tail(task, log)` put those on the wire already, and `Logs` deliberately asks
rather than assumes, because which files exist depends on what the task started.

**Three things are missing, and only the third is the reason for this file.**

- **The CLI cannot read any of them.** `porelo task` has nine subcommands and none opens a log. The
  contract is ahead of the terminal here, which is backwards for a tool whose first audience is at
  the machine.
- **Nothing is machine-wide.** Only `failures.log` and the clearance records live outside a task,
  and the first records crashes.
- **None of it survives.** A task's state directory is under `$XDG_RUNTIME_DIR`, cleared when the
  user's last session ends. So a machine's history is deleted by a reboot — measured twice, once as tasks listing no project and once as a task that could never be resumed.

### Why aggregating the seven files is the wrong answer

Seven files, seven formats: dnsmasq writes its own, each helper writes its own. Merging them by
time means parsing all seven, and **a parser that silently mis-orders entries is worse than no
view** — it produces a plausible story of what happened, which is the failure this project keeps
writing down. It would also be a read per file per task on every refresh, which is the listing
cost `since` and `behindMeasured` both exist to avoid.

The per-task logs stay. They are the detail. What is missing is the spine.

## The shape

### Notes

**This is a new subsystem, not a view.** Every moment worth recording needs a call added at the
place it happens, and each one is a decision about what is worth saying. That is the cost, and it
is the reason to argue the event list before writing any of it.

**A node is an OS user with a `porelod`,** so this is per user rather than per machine, and two
users on one host have two journals. Confirmed with the interface, whose machine list is keyed the
same way, so the two map one to one. **If this ever becomes host-wide it must be said loudly** - it
would be the single place where the two sides' meanings of "machine" diverge.

**The interface will want it, but the interface is not the reason.** An operator asking "what
happened here last night" has nowhere to look today, and that is true with no interface at all.

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

Read in the code at `30c8169b`.

**What is there now.** `porelo task logs` is built (`apps/app-task/.../TaskLogsCommand.java`), so the first missing
thing above is gone. A task's state directory is still `xdg.runtime()/<container>` (`TaskPaths`), so the seven
files still die at a reboot. Account-wide and durable, under `xdg.state()` (`PoreloPaths`): `failures.log`,
`stops.log` (each `task stop --all` and `panic`, with who and when), `grants.log` (whom a task acted as, [B31](README.md#b31)), the
clearance journals, and the message record (`MessageRecord`, the one hash chain). None of them is a spine.

**One log, not two.** [B154](README.md#b154) plans `EventLog` in `apps/app-model`, `$XDG_STATE_HOME/porelo/events.jsonl`, with the
daemon's `Events()`. This journal is that file: whichever of the two is built first creates `EventLog`, and the
other adds to it. What [B26](README.md#b26) adds is its event list, the cursor and the caps.

Steps:

1. Red first, in `apps/app-model`: `EventLog` appended past a cap keeps within it and writes a trimming event; made
   unwritable, `append` returns and the caller carries on; an event given a value that looks like a credential, a
   prompt or agent output is refused by the writer's shape (names and outcomes only, no free text field).
2. `EventLog.append(kind, project, task, outcome)`: one JSON line under `PIPE_BUF` with `O_APPEND`, each carrying a
   monotonic cursor; both caps (size and age) checked on append, trimming recorded.
3. The writers, one line each: `TaskControl` (start, stop, remove), the image build (`Preparation`), the hooks'
   repair (`SetupCommand`, and where `TaskLaunch` repairs them), the gate (a push arriving in `GitHttpServer`,
   `GateApproveCommand`, `GateRejectCommand` and the daemon's same calls), a clearance decision (`ClearanceHub`), a
   credential brokered for a task (the broker, once per credential per task rather than per request), and
   `failures.log`'s writer.
4. `Events(after: ?string)` in a new `55-events.varlink`, streaming with `more`; `porelo events [--follow]` reading
   the same file through the same reader, so both show the same order. `InterfaceDescriptionTest` holds them.
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

- **Concurrent appends** (Agent Core's). The daemon, the CLI and several helpers run as the same user and would
  write to one file. On Linux an `O_APPEND` write below `PIPE_BUF` is atomic, which would make one line per event
  safe without a lock - **this must be measured, not assumed**, and the line length bounded on purpose if it is
  what the design rests on. [B154](README.md#b154) plans a file lock instead.
- **Whether an event can be tied to its task without widening what an event holds** (Agent Core's). A security
  review asked for correlation and proposed carrying destination address, port and process. The journal carries
  names and outcomes; what is open is whether a task id alone is enough.
- **What the two caps actually are** (Agent Core's). Decided that there are two; the numbers should come from what a
  busy machine really writes rather than from a round figure.
- **Whether trimming and a hash chain can live in one file** (Agent Core's). [B154](README.md#b154) wants every line provable;
  [B26](README.md#b26) wants the file bounded. Trimming a chain needs the first kept line's predecessor hash kept as an anchor, or the
  chain restarts at each trim and says so.
