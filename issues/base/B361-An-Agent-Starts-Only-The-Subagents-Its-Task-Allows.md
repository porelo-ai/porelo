# B361 — An Agent Starts Only The Subagents Its Task Allows

**Status:** Accepted (operator, 2026-10-31).
**Size:** M - estimated 70 minutes from the median of M builds (47, p75 93): the value takes the path the turn limit
takes, from the task's start through the agent interface to the adapter, beside a declared block in the agent
definition and its reader.

**What must be true.** Porelo's agent interface carries how many subagents an agent may start inside a task, the
same for every adapter, and none unless the task says otherwise. Each agent definition declares what its adapter
can enforce of that number: only off, or a count. Porelo refuses a task asking for a value
the agent cannot enforce, such as any subagent on an agent that can only be switched off, before the task starts,
instead of enforcing less than was asked. The adapters, each in its own repository, turn the value into their
agent's own switch.

The value is one number (operator, 2026-10-31): 0 means none, 1 or more the most subagents the agent may run inside the
task at the same time, not over the whole task (operator, 2026-10-31); a total over the task belongs to the hard bound
named below. It is given as `--subagents N` and as one number in the project file. There is no depth in it; where an
agent's own switch needs a depth for the count to hold, its adapter sets that itself and says so in its issue.

Michi's decision of 2026-10-31 (operator): the soft switch, adapter-independent, default 0. A hard bound in Porelo
itself, a process limit in the task's container or a cap at the provider proxy that also counts an agent starting
itself again through its shell, is an Idea of its own in `porelo-project`. What each agent offers today was read
from the binaries, not measured (`~/.porelo/handover/porelo-claude-code/subagent-limit-survey.md`): Claude Code, Codex
and omp can be switched off and bounded in number and depth; agy can only be switched off, through a hook; Pi has
no subagents.

## Acceptance

- A task started without a subagent setting gives its agent a value of 0, and the adapter receives it.
- An agent definition that declares no subagent support is started only with 0, and a task asking more is refused
  with 64, naming the agent and what it can enforce.
- A definition that declares a count gets the task's number as asked.
- The value is part of what `porelo-agent-api` publishes, so an adapter in another repository builds against it.
- Seen to fail first: today no value reaches an adapter, and a task cannot ask for one.

## To be checked

- Where a task names its value: the project file, `porelo task run`, or both, and which wins.
- How a definition declares what it can enforce, beside what `AgentDefinitionReader` reads today, and how an older
  definition without it is read (as able to enforce nothing but 0).
