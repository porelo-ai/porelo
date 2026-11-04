# B361 — An Agent Starts Only The Subagents Its Task Allows

**Status:** Review. Accepted.
**Size:** M - estimated 70 minutes from the median of M builds (47, p75 93): the value takes the path the turn limit
takes, from the task's start through the agent interface to the adapter, beside a declared block in the agent
definition and its reader.

**What must be true.** Porelo's agent interface carries how many subagents an agent may start inside a task, the
same for every adapter, and none unless the task says otherwise. Each agent definition declares what its adapter
can enforce of that number: only off, or a count. Porelo refuses a task asking for a value
the agent cannot enforce, such as any subagent on an agent that can only be switched off, before the task starts,
instead of enforcing less than was asked. The adapters, each in its own repository, turn the value into their
agent's own switch.

The value is one number: 0 means none, 1 or more the most subagents the agent may run inside the task at the same time,
not over the whole task; a total over the task belongs to the hard bound named below. It is given as `--subagents N` and
as one number in the project file. There is no depth in it; where an agent's own switch needs a depth for the count to
hold, its adapter sets that itself and says so in its issue.

**Not kept today.** The number reaches each adapter, and no adapter turns it into its agent's switch: `src/main` of
porelo-claude-code, porelo-agy, porelo-codex and porelo-omp names no subagent setting. So a task with 0, the default, may
still start subagents, while `doc/project-file.md` says it runs none. Read, not run.

Decided: the soft switch, adapter-independent, default 0. A hard bound in Porelo
itself, a process limit in the task's container or a cap at the provider proxy that also counts an agent starting
itself again through its shell, is an Idea of its own in `porelo-project`. What each agent offers today was read
from the binaries, not measured (a survey kept outside the repository): Claude Code, Codex
and omp can be switched off and bounded in number and depth; agy can only be switched off, through a hook; Pi has
no subagents.

## Acceptance

- A task started without a subagent setting gives its agent a value of 0, and the adapter receives it.
- An agent definition that declares no subagent support is started only with 0, and a task asking more is refused
  with 64, naming the agent and what it can enforce.
- A definition that declares a count gets the task's number as asked.
- The value is part of what `porelo-agent-api` publishes, so an adapter in another repository builds against it.
- Seen to fail first: today no value reaches an adapter, and a task cannot ask for one.

## Done

- The task names its number with `porelo task start --subagents N`, in the project file as `limits.subagents`, and
  per repository as `repositories.<name>.limits.subagents`, replacing the project's key by key like the other limits.
  The flag wins, then the repository's key, then the project's, then 0. Below 0, or not a whole number, is refused:
  the flag below 0 with 64, a value that is no number by the parser with its usage code 2, a bad key in the file as a
  project-file error. The daemon's `Start` takes `subagents: ?int` and refuses with 64, before it asks the runtime
  anything, whatever is not a whole number from 0 to 2147483647, the largest the flag takes: a fraction such as -0.5
  or 2.7, a number past the bound such as 1e11, a string or a boolean, none of them rounded or dropped for the
  project file's. The agent API's server refuses the same values of `subagents` in `BuildCommand` and
  `ContainerSetup` (`SubagentLimit.wholeNumber`, `SubagentLimit.MOST`).
- The settled number is kept with the task, in its profile (`task.json`, `TaskProfile.subagents`, schema 7), which a
  reboot saves and puts back with the rest of its record. A start that brings a stopped task back - from the command
  line, the daemon's `Start` or after a reboot - with a prompt hands its agent that number again in the run request;
  a profile from before it was recorded reads as 0, which is what its agent was told. `--subagents` on such a start
  is taken when it is the kept number and refused with 64 otherwise, naming the kept one.
- An agent started attended (`--attach agent`, the default without a prompt) hears its number only from its setup,
  so a setup that fails - the adapter's `ContainerSetup` refusing or a file it returns not written - ends the start
  with 70 and the container stopped, naming the agent and the number, and nobody is put into it. A shell start and an
  unattended run keep the earlier behavior, saying the files could not be prepared and going on: a shell starts no
  agent, and an unattended run carries the number in its own run request. This is the narrowest rule that never
  starts an agent without its switch: whether a setup's files carry the switch cannot be told from outside the
  adapter, and every setup carries the number, 0 included.
- The daemon's `CanStart` answers the new outcome `SUBAGENTS_REFUSED` when the project file's `limits.subagents`, or
  the repository's, asks the selected agent for more than its definition enforces, with Start's refusal as its
  `detail`, word for word, before the provider and the credential are asked about, as Start refuses it.
- An agent definition declares `subagents: off` or `subagents: count`, one scalar at the top level. `off` holds only
  0, `count` any number at the same time; a definition without the key is read as `off`. YAML reads an unquoted `off`
  as `false`, which is taken as the same declaration; any other value is refused naming `subagents`. The definition's
  JSON carries it as `"subagents": "off" | "count"`, absent from an older adapter meaning `off`. `porelo agents
  --verbose` shows it.
- A number the agent cannot hold is refused with 64 before a credential is looked at and before anything is built or
  created, in a dry run too, naming the agent, that it can only switch its subagents off, and where the number came
  from (`--subagents 2`, `limits.subagents 1`), with `--subagents 0` as the way out.
- The agent API, as `porelo-agent-api` publishes it: `SubagentLimit` (`OFF`, `COUNT`, `enforces(int)`),
  `AgentDefinition.subagents()`, and an `int subagents` in `RunRequest` and in `SetupContext`, sent as the parameter
  `subagents` in `BuildCommand` and in `ContainerSetup`, 0 included; an older Porelo that sends none is read as 0.
  Both carry it because an attended agent is started without a run request, so its setup is where its adapter hears
  the number. Constructors without it remain and mean 0. The shared command builder refuses a request past what the
  definition declares. The adapter turns the number into its agent's own switch.
- The stub declares nothing and so holds only 0, which lets the acceptance suite show the refusal. To show the number
  arriving, its `containerSetup` could write it into a file in the container that a scenario reads.
