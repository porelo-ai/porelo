# B194 — A File Handed In At Every Start

**Status:** Analyzed.
**Size:** L - the hand-in at every start is small, built on `HandIns`; the way back is a new path out of a task, which
the decision "nothing takes a file out of a task" excludes today, and needs that decision changed first.

**What must be true.** A file the person keeps on the host - an agent's `.AGENTS.md`, what holds on this machine and
the person's preferences - is handed into the agent's task read-only at every start. An agent that wants it changed
leaves its changed copy as a proposal; Porelo writes it on the host only after the person approved the change, so
nothing a task wrote reaches the next task unseen.

From [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39), where this file takes the place of the agents' own memory.

## Plan

Read on 2026-10-21 in `HandIns`, `TaskGiveCommand`, `TaskTakeBackCommand`, `TaskLaunch` and `doc/decisions.md`.

1. **What is handed in, and where it is kept.** The person's own directory
   `~/.config/porelo/projects/<project>/hand-in/`: every regular file there, at its top, is handed in. It is the
   person's, not the project file's, since the file is what holds on this machine and is never in a repository. A
   repository may have its own below it, `hand-in/<repository>/`, which wins by name.
2. **At every start**, after the container is up and before the agent is attached, `TaskLaunch` hands each file in
   through `HandIns` as `porelo task give` does: whole or not at all, to `/porelo/files/<name>`, root's and read-only
   to the agent, each one written down with its size and sha256. A file over `limits.hand_in` is refused by name and
   the start says so; a missing directory hands in nothing. The start lists what it handed in.
3. **The way back - an open question below.** Under (a): the agent writes its proposed copy to
   `/porelo/proposals/<name>`, a directory it may write and nothing else reads during the run. `porelo task proposals
   TASK` shows each proposal as a diff against the host's file, and `--approve NAME` writes it on the host, only then,
   as an atomic replace with the old file kept beside it. A proposal is text, at most `limits.hand_in`, and is never
   executed or read by Porelo beyond the diff.
4. **Docs:** `doc/running.md`'s hand-in section, and the decision in `doc/decisions.md` changed with its exposure.
5. **Tests, red first:** a start with two files in `hand-in/` places both, read-only to the agent and written down; a
   file over the limit refuses the start by name; under (a), a proposal is never written on the host before
   `--approve`, and an approved one replaces the file whole.

## Open questions

- **The way back for a changed copy** - today's decision says nothing takes a file out of a task:
  (a) a proposals directory the person reviews as a diff and approves with `porelo task proposals --approve`, which
  changes the decision to "nothing leaves a task unseen", with the exposure that a person who approves without
  reading lets the agent change its own instructions; or (b) no way back: the agent says in its answer what it would
  change, and the person edits the file. Recommended: (a), since it is what the issue asks and the review stands
  between the task and the host.

## To be checked

- Whether a hand-in right after the start races the agent's first read of `/porelo/files`; if so, the files are
  placed before the agent's process starts.
