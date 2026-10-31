# B371 — A History Judged Under The Rules Of Its Time

**Status:** Idea.
**Size:** M, proposed - a second mode of `porelo commit verify` that reads the signers with their `valid-before` and
each task certificate at its commit, with its tests.

**What must be true.** A host can check a whole migrated history before it arms its pre-receive hook, under the rules
that held when each commit was made, not only under the rules of now. Today `porelo commit verify`
([B363](README.md#b363)) judges every commit at its arrival: only keys in force, without `valid-before`, and task
certificates valid now, so a history with since-retired keys or since-expired task certificates is refused as a
whole.

A history mode takes the signers with their `valid-before` and judges each commit by the key in force at its own
commit time, and a task's signature by its certificate's validity at that time. A commit's own time is not to be
trusted; what bounds it must be named: the order of the history, the signed commits around it, or the times the
forge or the central forge recorded.

The coordinator decided on 2026-10-31, for Agent Machines' bring of a project to a host, that the bring arms the
hook right after the migration and records the migrated commits as `migrated`, not judged here, so this mode is not
needed for sprint 6m; it is for michi to place.

Open: what bounds a commit's time when its own date is the signer's word; whether the mode reads the central
forge's record of when each commit arrived there.
