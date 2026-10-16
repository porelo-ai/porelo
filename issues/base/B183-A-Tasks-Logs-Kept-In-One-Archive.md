# B183 — A Task's Logs Kept In One Archive

**Status:** Review.
**Size:** M - an option on `task logs`, an archive of the task's logs with three answers rendered as text, a checksum
list, owner-only permissions, a daemon call for an interface, unit tests and one acceptance scenario.

**What must be true.** `porelo task logs TASK --zip [FILE]` writes what a task did that matters for security into one
archive, and prints its path:

- **Its logs:** every log of its helpers on this machine - gate, broker, relay, resolver, clearance watcher - and
  `events.jsonl`, what the firewall stopped.
- **Three answers as text:** what `task status`, `task files` and `talk log` say about it. One file then answers
  "what did this task do".
- **Not the agent's own words** (`task.log`) unless asked for with `--with-agent-output`, since they may hold
  confidential data.
- **Name and place:** by default `porelo-logs-<task>-<UTC time>.zip` in the current directory, readable by the owner
  only, with a list of SHA-256 checksums inside.

Today these logs live only under `$XDG_RUNTIME_DIR/porelo/<container>/`, and they are gone at a restart - under WSL
also at `wsl --shutdown`. The only way to keep them is zipping that directory by hand, which needs the container name
and the runtime path.

## Plan

Read in today's code:

- `TaskLogsCommand` lists a task's logs, or prints one, from `TaskInventory.logs(container)`. That reads
  `TaskPaths.containerState(container)` and keeps the files `TaskInventory.isLog` takes: `*.log`, `events.jsonl` and
  `reader.err`. The agent's `task.log` is one of them.
- The task is found with `TaskTarget.resolve`, by its container name, its own name, or the checkout one stands in.
- `TaskStatusCommand`, `TaskFilesCommand` and `TalkLogCommand` (in `app-messaging`) each print their answer.
- Nothing in Porelo writes a zip yet. `java.util.zip` is in the JDK and needs nothing more in the native image.

The plan:

- **`--zip [FILE]` on `task logs`:** the same task resolution, so the checkout one stands in works too. `FILE` is
  optional; without it the archive is `porelo-logs-<task>-<yyyyMMdd'T'HHmmss'Z'>.zip` in the current directory. An
  existing file is never overwritten: it is refused, and names the file.
- **What goes in, under `<task>/`:**
  - every file `TaskInventory.logs` lists except `task.log`, taken as it is at that moment;
  - `status.txt`, `files.txt` and `talk.txt`: what the three commands print, rendered by the same code, not by
    running them as processes. A part that cannot be answered - a stopped task has no status of its container - is
    written as the sentence that says why, so a missing part is never silent;
  - `task.log` only with `--with-agent-output`;
  - `SHA256SUMS`, last: one line per file in the form `sha256sum -c` reads, so the archive can be checked with tools
    every machine has.
- **Permissions:** written as `rw-------` from the start - created with that mode, not changed after - so there is no
  moment where another account could read it.
- **A task without logs** (a restart took them, or nothing wrote one): it still writes the three answers, and says
  that no logs were there and why.
- **The daemon:** a call `LogArchive(task, withAgentOutput)` in `15-task.varlink` writes the archive under
  `$XDG_STATE_HOME/porelo/archives/` and answers its path. That is the place an interface can offer to save it from,
  because a daemon has no "current directory". The CLI writes where it stands.

**How it meets [B154](README.md#b154).** That issue keeps one hash-chained, append-only log of an account's
security-relevant events, which outlives a restart and `porelo clear`. That log is the record. This archive is a copy
taken by hand, for keeping or for handing to somebody. Once that log is built, the archive adds the task's own entries
from it (`events-of-task.jsonl`), with the chain position of the first and the last. That way a copy can be checked
against the account's log, and so against an edit after the fact. Until then the archive's own checksums show only
that the archive was not changed after it was written, and its README says so.

Steps:

1. **Red first:** `TaskLogArchiveTest` with a state directory in a temporary directory:
   - which files go in, with `task.log` left out and then in with `--with-agent-output`;
   - the checksum lines checked against the bytes;
   - the mode `rw-------`;
   - an existing file refused;
   - a task with no logs.
2. `TaskLogArchive` in `app-model` (the archive), the option in `TaskLogsCommand`, and the daemon's `LogArchive`.
   `doc/commands.md` gets the row.
3. **Acceptance:** a stub task leaves a gate log, the archive is written, `unzip -l` lists the parts,
   `sha256sum -c` passes, `stat` shows `600`, and `task.log` is absent by default.
4. Reactor, documents and the suite on the VM (`not @restart`).

## Open questions

None. Its three defaults were confirmed:
- `task.log` goes in only with `--with-agent-output`;
- the daemon writes under `$XDG_STATE_HOME/porelo/archives/`;
- an existing file is refused, never overwritten.

## As built

- `TaskLogArchive` in `app-model` writes the archive.
  - It starts with `README.txt`, which says what each part is and what the checksums do and do not show.
  - A log that is a link is left out, never followed.
  - An archive that fails half way is removed.
- `TaskLogsCommand.archive` is shared by the CLI and the daemon. It renders `status.txt`, `files.txt` and `talk.txt`
  with picocli in this process, through `PoreloFactory`. A refusal is kept with its exit code.
- `LogArchive(task, withAgentOutput)` in `15-task.varlink` writes under `$XDG_STATE_HOME/porelo/archives/` (`rwx------`)
  and answers the path and the files.
- **Acceptance:** in `task-unattended.feature`, beside the unattended run, which leaves a `task.log` to be left out.

