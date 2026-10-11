# B174 — A Running Task's Commits And Their Diff

**Status:** Idea.

**What must be true.** A client can ask the daemon which commits a running task has made since its start - each
commit's id, subject and time - and the diff of one of them or of all together, without the work having reached the
gate. Today the contract gives only counts (`WorkHeld.unpushedCommits`, `AgentEnded.unpushed`), so the interface
cannot say in words what a piece of work did until it is pushed.

Asked for by `sokar-frontend`'s
[F112](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md#f112), which is blocked by it.
