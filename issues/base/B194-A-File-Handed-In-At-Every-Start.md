# B194 — A File Handed In At Every Start

**Status:** Accepted.

**What must be true.** A file the person keeps on the host - an agent's `.AGENTS.md`, what holds on this machine and
the person's preferences - is handed into the agent's task read-only at every start. An agent that wants it changed
leaves its changed copy as a proposal; Porelo writes it on the host only after the person approved the change, so
nothing a task wrote reaches the next task unseen.

From [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39), where this file takes the place of the agents' own memory.
