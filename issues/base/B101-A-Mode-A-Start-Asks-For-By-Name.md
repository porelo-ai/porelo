# B101 — A Mode A Start Asks For By Name

**Status:** Analyzed.
**Size:** M - the agent definition every adapter writes, the `Start` and `Agents` contract, and the start.

**What must be true.** A person starting a task can ask for one of the agent's named modes, such as
`read-only`, and nothing reaches the agent that its definition did not write.

## Why

Asked for when a read-only review run was wanted in a task with OMP, and `porelo task start` had no way to say
"read only". The agent's side is each adapter's own: it declares its modes.

### What is missing

A start gives an agent only what its definition declares - `--model`, `--max-turns`, and Porelo's own `--minutes` -
and that is deliberate: the definition sets the flags that decide what an agent may do (`--auto-approve` among
them), and an option that passed any flag through would let a start undo them. So a run that should only read and
write one report had nothing but its prompt to say so, although OMP itself can enable only named tools.

## Plan

Read in the code on 2026-10-11: an agent's `headless:` section is read by `AgentDefinitionReader`
(`agents/api/`) into `HeadlessFlags` (`model_flag`, `max_turns_flag`, ...), carried to Porelo as JSON by
`AgentDefinitionJson`, and assembled by `HeadlessCommandBuilder` from a `RunRequest` (`model`, `maxTurns`).
`TaskRunCommand` takes `--model` and `--max-turns`; `Start` in `15-task.varlink` takes `model` and `maxTurns`.
**`Start` already has a `mode`** - `shell`, `agent` or `unattended` (`TaskMode`) - so the agent's named mode
needs another name on the wire.

**A thinking level goes in this issue too**, by the same path: a definition declares a flag and the values it
takes, a start names one, and nothing else reaches the agent. Two keys, one reader, one contract change.

1. **The definition**: `headless.modes: {read-only: ["--tools=read,grep,..."]}` and
   `headless.thinking: {flag: "--thinking", levels: [off, low, ..., auto]}`, read by `AgentDefinitionReader`
   into `HeadlessFlags.modes()` and `HeadlessFlags.thinking()`. A mode's flags are the definition's only.
2. **`RunRequest`** gains `agentMode` and `thinking`; `HeadlessCommandBuilder` appends the mode's flags and the
   thinking flag with its value. An undeclared name or level throws before any command line is made.
3. **`AgentDefinitionJson`** carries both, so a packaged adapter's definition reaches Porelo; the adapters declare theirs.
4. **`porelo task start --mode <name> --thinking <level>`**; `Start` takes `agentMode: ?string` and
   `thinking: ?string`, refused as `InvalidParameter` naming what the agent declares.
5. **`porelo agents --verbose` and `Agents`** list the modes and the levels.

Red first: `HeadlessCommandBuilderTest` with a mode the definition does not declare, which builds a command
today because nothing reads it; then a test that reads the built command line for a flag the definition did not
write.

## Acceptance

- **An agent definition can declare named modes**, each a set of its own flags - say `read-only`, enabling only its
  reading and searching tools and one write.
- **A start asks for a mode by name** (`--mode read-only`); a name the agent does not declare is refused, and no flag
  reaches the agent that its definition did not write.
- **`porelo agents --verbose` lists the modes**, and the interface offers them.
- **A start asks for a thinking level** (`--thinking high`) among the levels the definition declares; another is
  refused.
- **Seen to fail:** a test that starts with `--mode` naming an undeclared mode goes red when the start is not
  refused; a test that reads the agent's command line after a start with a mode goes red when it holds a flag the
  definition did not write; a test of `porelo agents --verbose` goes red when a declared mode is missing; a test
  with an undeclared thinking level goes red when the start is not refused.

## To be checked

- The wire name of the agent's mode, since `Start.mode` is taken: `agentMode` is the plan's (Agent Core's, with
  the interface's agent).
- Whether a mode applies to an attended start as well, where the agent's own interface could switch tools back
  on (Agent Core's).
