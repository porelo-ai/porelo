# B514 — Every Task Run Kept, In One Timeline

```json
{
  "$schema": "issue-1.json",
  "id": "B514",
  "title": "Every Task Run Kept, In One Timeline",
  "state": "Backlog",
  "size": "M",
  "priority": "Low",
  "rank": 15,
  "blockedBy": [],
  "covers": "Every task run kept in one timeline: lifecycle entries (who and when, the run's numbers) in the events chain, logs archived past stop, reboot and removal, readings over varlink, MCP and the CLI, one structured log stream for every Porelo process with the journal as its home; porelo-frontend's timeline reads it."
}
```

**Why this size.** mostly re-routing the existing log sources into one stream. Four pieces of new code beyond that: the
lifecycle entries, the copy on stop with the archive on Remove, the `podman events` follower, and the readings.

Filed 2026-11-08 at the operator's word; to be built after [B515](README.md#b515) (the operator,
15:23Z). The OTLP export is split off as [B516](README.md#b516).

**What must be true.** A task's whole life can be read, in the CLI and, through the readings below, in the window,
for as long as the person keeps it, also after the task is stopped, the machine rebooted, or the task removed.
- **Each run's lifecycle:** created, started, resumed, stopped, ended, removed and pruned. Each entry says when, and who:
  the person, an agent over MCP, a parent task, or porelod itself.
- **Each run's numbers.**
- **Each run's logs.**
- All of it is in one timeline, read by one command, with every Porelo process's records correlated by task and run.

## Why

On 2026-11-08 the operator found:
- `porelo events` shows no start or stop;
- porelod's journal says only `start <task>: exit N`;
- a stop is logged nowhere;
- a task's logs live under `/run` (tmpfs);
- `porelo-default-mcp-test-1`, removed by hand with podman at 14:08:37Z, left no line in Porelo, and its logs were gone.

Today a task writes nine files under `/run/user/<uid>/porelo/<task>/`: task, clearance, dnsmasq, gate, hooks, vault,
workspace, reader.err and events.jsonl. The account writes more under state:
- `events.jsonl`;
- `failures.log`, 15 MB of stack traces, unbounded;
- `stops.log`, `operations.json` and `usage/*.jsonl`.

On top of that, porelod writes the journal, and porelo-mcp's calls appear only in the MCP client's own log. Nothing
correlates them, most vanish with `/run` or with the task, and nothing can be collected from a developer's machine or
a host.

## Analysis

The coordinator's proposal of 14:20Z and 14:24Z, read against the code on 2026-11-08: what fits, and what Core would
change.

**The lifecycle goes into the events chain, as `task-*` kinds, not into a chain of its own.**
- `events.jsonl` is already append-only and hash-chained, `events --verify` already checks it, and `Events`
  (`55-events.varlink`) already reads it with filters.
- A second chain would be a second thing to verify and a second timeline to merge.
- Within Tasks1 only the comment on `kind` grows, as the MCP endpoint's `started` did. `started` becomes one of
  the `task-*` kinds.

**Who did it** is known where the call comes in, never from a field:
- the person's socket means the person (CLI or GUI);
- a task's MCP socket means that task, from the MCP endpoint's `CALLER`. The MCP client's name and session are known only to
  porelo-mcp, and they need a field in the binding with Agent MCP, marked as the client's word, not proven;
- porelod's own acts (expiry, prune, a failed start, a reboot found at start) name porelod.

**A container removed outside Porelo** (by hand with podman, as on 2026-11-08) cannot be prevented. porelod can follow
`podman events` for its own containers and record `task-removed` with "outside Porelo", saving what is left of the
logs first. Same-user deletion of the archive or the chain can be detected (`events --verify` shows the gap), not
prevented.

**The archive:**
- on stop, a run's logs are copied from `/run` to state, so a reboot keeps them;
- on Remove, they and the run's records go into one compressed archive under state before the container is deleted,
  and the archive's digest is named in `task-removed`;
- removing an archive is its own command and its own entry. It is never offered over MCP: `McpMethods.NEVER`, beside
  `Remove`.

**"ended" carries the run's numbers:** exit code, duration, CPU, peak memory, network, provider requests and
tokens/cost from `usage/`, blocked connections and hand-ins. Most are already measured, in `TaskUsage`, the gate and
the clearance. What is new is that they are kept per run.

**One stream** is right; Core would change three things:
1. **The journal as its home on Linux**, with structured fields (`PORELO_TASK`, `PORELO_RUN`, `PORELO_COMPONENT`),
   written over the journal's native socket. That needs no library. Where there is no journal (a container image, a
   Mac later), the same records go to one file per run under state, written by the same writer. The nine files
   become views of the stream, not files, switched over in one go, with a test per view that it answers as the file
   did.
2. **Bound what is unbounded first:** `failures.log` gets the size limit and rotation the stream has. That can come
   before everything else.
3. **The export is its own issue,** [B516](README.md#b516).

**The audit records** (events: approvals, clearances, credentials and now the lifecycle) stay the hash-chained subset
whose trust does not depend on the stream. They are mirrored into the stream, so the stream is the one timeline to
read and the chain is the one to trust.

## Research (2026-11-12)

The operator's research on AI agent observability tools (in `porelo-project` ST137 and ST341 to ST343), taken in:
- **No collector or tool replaces this issue.** A collector buffers, and it can lose data even with `file_storage`
  (opentelemetry.io/docs/collector/resiliency). Langfuse ingests traces only, not OTLP logs. So the local journal
  outside the task container stays the truth for:
  - the run, task and session ids, and their order;
  - restart and abort states;
  - retention;
  - the CLI's readings.
- An export, a receiver or a view is derived from that journal ([B516](README.md#b516), [B644](README.md#b644)), never a
  second truth.

Open questions, for the plan reading:
- Does "kept" mean until the person deletes it, and within what disk budget, said and bounded?
- Are the lifecycle and the metadata enough, or is a full replay of the dialog and the tool calls needed? A replay
  holds prompts, tool arguments and output, so it is a trust decision of its own.

## Plan

One issue, in this order:
1. **The lifecycle (L):**
   - the `task-*` kinds with who and when;
   - porelod's journal line for each;
   - `ended` with the run's numbers;
   - `podman events` followed for removal outside Porelo;
   - `porelo task history [task]`;
   - the reading `Lifecycle(project?, task?, since?)`, offered over MCP as a reading.
2. **The archive (M):**
   - the logs copied to state on stop;
   - the compressed archive on Remove, with its digest in the entry;
   - `ArchivedLog(task, run, log)`;
   - `porelo task logs <task> [--run N]` for removed tasks;
   - archive removal as its own command, never over MCP.
3. **One stream (L):**
   - a writer for every Porelo process;
   - the journal with structured fields, or the file where there is none;
   - `porelo logs [--task] [--component] [--since] [--follow]`;
   - the nine files turned into views in one go, each with a test that it answers as before;
   - `failures.log` bounded at once.

**For every reading** (the window's timeline is sized on them):
- lifecycle entries and stream records by filter (project, task, run, component, level, since), in pages with a
  cursor, so a reconnect resumes with no gap and no double;
- a follow that pushes new records and ends with the run;
- secrets kept out at the source: the vault proxy, the gate and the hooks never write a value, since a reader shows
  `data` as it comes;
- `failures.log`'s stack traces as records of level error, with the trace in `data`, bounded like the rest.

**Plan reading** before it is built: the lifecycle and the archive cross a trust boundary.

**Frontend:** porelo-frontend's timeline reads the lifecycle and the archive through `Lifecycle` and `ArchivedLog`, and the
stream through `porelo logs`' reading.
