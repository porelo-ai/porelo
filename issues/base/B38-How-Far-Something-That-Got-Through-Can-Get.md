# B38 — How Far Something That Got Through Can Get

**Status:** Analyzed; blocked by sokar [B26](README.md#b26).
**Size:** L - a field in the agent definition every agent repository fills, the deliveries wrapped, and a count kept.

**What must be true.** Content Sokar itself delivers into a task carries its origin, so the agent reads it as
data from a known source and a reviewer sees where it came from; and "how far did it get" has an answer after the
task is gone, including what it installed.

Content that arrives by `git clone` is never seen by Sokar and stays unmarkable; that is the difference worth
stating. [B14](README.md#b14)'s messages already arrive as A2A messages from a named peer, in the inbox and never through the
channel that carries the operator's instruction. A handed file arrives only in `/sokar/files`, root's and
apart from anything the task wrote, and its record names who handed it in - a build's verdict and logs among
them, handed in by Sokar itself ([being told what the build did](../../doc/running.md#being-told-what-the-build-of-a-push-did)). How the origin is labeled is not Sokar's to decide: fencing conventions
differ per agent, so it is a field in the agent's definition, not a branch here.

## Plan

Read in the code at `30c8169b`.

- **Where Sokar delivers content.** Every delivery goes through `HandIns` (`apps/app-model/.../HandIns.java`): a
  person's `HandIn`, and `BuildWatch` (`apps/app-task/...`), which hands each build's verdict (`<base>.txt`) and the
  failed jobs' logs (`<base>-<n>.log`) into `/sokar/files`. Messages arrive in the mailbox as A2A messages from a
  named peer and are out of scope here. An artifact ([B15](README.md#b15)) will arrive through `HandIns` too. So one place wraps
  them all.
- **The agent definition** is `AgentDefinition` (`agents/api/...`), read by `AgentDefinitionReader` from each agent's
  manifest, and it has no field for how an agent fences content. Adding one is a change every agent repository
  reads.
- **Nothing counts what a task installed.** No `podman diff` is taken anywhere; `TaskControl.remove`
  (`apps/app-task/.../TaskControl.java`) removes the container through `context.podman().remove`.

Steps:

1. **The field (M).** `AgentDefinition.origin`: a template with `{source}` and `{content}`, or absent; absent means
   Sokar adds a header line of its own (`Handed in by <who>, <when>, sha256 <digest>`) above text and a sibling
   `<name>.origin` file beside a binary. `AgentDefinitionReader` reads it; the stub agent (`agents/stub`) declares
   one. Red first: a test changing the stub's template and nothing else, red while the delivered text is unchanged.
2. **The wrap (S).** `HandIns.give` applies it to text it places (a build log, a verdict, a handed text file), from
   the record line it already writes - who handed it, when, its digest - and leaves binary content as it is, with
   the `.origin` file. `sokar gate review` names, beside a pushed commit, what had been handed in before it (from
   the `HandIns` record).
3. **The count (S, needs [B26](README.md#b26)).** `TaskControl.remove` takes `podman diff` of the container before removing it and
   writes the number of added and changed paths into the machine's journal ([B26](README.md#b26)) beside the task's name, with what
   it reached for (the egress report) and what it pushed (the gate). Red first: remove a task, then no place answers
   what it installed.
4. The agent repositories each add the field to their manifest, once the stub shows its shape.

## Acceptance

- Content Sokar delivers into a task - a build log, a handed file, an artifact - carries its origin in the form
  the agent's definition declares, and `sokar gate review` shows the same origin beside what came of it. Seen
  to fail: an agent definition that changes its declared form and nothing else leaves the delivered text
  unchanged, or a delivery that reaches the task unlabeled.
- After a task is removed, what it installed (the `podman diff` count taken before removal) can be read by a
  person, beside what it reached for and what it pushed. Seen to fail: `task remove`, then no place that
  answers what the task installed.

## To be checked

- **Whether the record can answer "how far did it get" after a reboot** (Agent Core's). The `HandIns` record and
  `grants.log` outlive the task, but what it reached for and its other logs are in its state directory under
  `$XDG_RUNTIME_DIR`, which a reboot clears; only [B26](README.md#b26)'s journal answers then, which is why this waits for it.
- **Whether binary content gets a sibling `.origin` file or nothing** (Agent Core's). A label inside a binary would
  break it; a sibling file is readable by the agent but easy to miss.
