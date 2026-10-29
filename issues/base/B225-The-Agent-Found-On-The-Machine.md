# B225 — The Agent Found On The Machine

**Status:** Analyzed; blocked by [B224](README.md#b224).
**Size:** L - a method in the contract three agent repositories implement, the choice every start, its dry run and
the shield share, a new `project.yml` key, the import offered at the start, and the daemon's answers naming the
choice and the findings; several modules and two contracts.

**What must be true.** A task started without `--agent` and `--provider` gets the agent the person already uses on the
machine, signed in, with that provider, and the start names the choice and where it was found; a flag given still wins.
Porelo's part: the method `Find`
in the agent contract, answering presence only, never a credential's value, with "cannot say" in its answer, and the
choice at the start that reads it.

## Plan

Read in the code on 2026-10-20 (`agents/api`, `apps/app-agents`, `apps/app-task`, `apps/app-vault`).

1. **`Find` in `Agent1`**: `Find(configDirectory) -> (signedIn: SIGNED_IN | NOT_SIGNED_IN | CANNOT_SAY, credential:
   ?string, provider: ?string, findings []Finding)`, a finding `{what, path}`, never a value. `Agent.finder()`
   defaults to `CANNOT_SAY`; `AgentServer` registers it; `InstalledAgent.find` calls it through
   [B224](README.md#b224)'s `optional`, so an agent without it reads as `CANNOT_SAY`. `AgentProtocol.VERSION` goes to
   4. The stub reports what `PORELO_AGENT_STUB_FINDINGS` names, for the suite.
2. **The command on `PATH`**: `porelo` looks up each installed agent's `binary` (`AgentDefinition`) on the
   account's `PATH` once per choice; a hit is a finding of `porelo`'s own, "the person uses this agent", since an
   agent runs its own pinned command inside the task, never the host's.
3. **The order, in `AgentChoice`**, which today is a pure function of the installed agents and `--agent`
   (`considerAgent`, `:70`) and is shared by `StartCheck` (`:838`), `TaskLaunch` (`:1790`) and `EgressControl`
   (`:185`), so the dry run, the start and the shield name the same agent:
   1. `--agent`;
   2. the project's `agent`, a new key in `ProjectSchema` and `ProjectReader`, refused when no such agent is
      installed;
   3. exactly one agent found signed in;
   4. several signed in, or several installed and none signed in: `SEVERAL_AGENTS` as today, its detail listing the
      signed-in ones first;
   5. one installed: that one, as today.
   `Choice` gains `source` (`FLAG`, `PROJECT`, `SIGNED_IN`, `ONLY_ONE`) and the findings' paths. `Find` is asked
   only when the first two do not decide, and every start asks again; nothing is remembered.
4. **The start names it**: one line before anything is made, for example `agent claude, provider anthropic
   (subscription), found in ~/.claude`; `StartCheck`'s answer carries `source` and the path, so the interface names
   it without deriving it.
5. **The import offered**: a start at a terminal whose chosen agent was found signed in but has no credential in
   the vault offers `vault import` for it (`CredentialImport`), as the start offers `vault login` today; without a
   terminal it refuses as today, naming the command.
6. **The provider**: an agent found signed in to a provider it can drive gives that provider when `--provider`
   is not given; `SelectedProvider.consider` takes it as its third argument.
7. Docs: `doc/running.md`'s choice of an agent, `doc/project-file.md`'s `agent`, `doc/commands.md`.

**Red first:** `AgentChoiceTest` with fake agents: two installed, one answering `SIGNED_IN`, is chosen with source
`SIGNED_IN`; one answering `CANNOT_SAY` and one `NOT_SIGNED_IN` still refuse with `SEVERAL_AGENTS`; `--agent` wins
over a signed-in one; a credential's value in a finding fails the reader.

## Acceptance

- With two agents installed and the stub reporting itself signed in, `task start` without `--agent` takes the stub
  and prints where it was found; with `--agent` naming the other, the other.
- An agent built before `Find` is chosen as today and named as "cannot say", never as not signed in.
- No finding, answer or line printed carries a credential's value.

## The daemon's answers, agreed with the interface

- **`StartCheck` and `Start` already take `agent` as optional** (`15-task.varlink:411`, `:718`) and choose through the
  same `considerAgent`, so an interface that sends none gets the order above; their comment that a project file
  names no agent changes with the new key.
- **`Readiness`** gains `agentSource` (`FLAG`, `PROJECT`, `SIGNED_IN`, `ONLY_ONE`; a client says an unknown value as
  it comes) and `agentFoundIn`, the path, beside its `agent`.
- **Under `SEVERAL_AGENTS`**, `signedIn`: the names of the agents found signed in, as a list, since no client parses
  `detail`.
- **`Agents`** gains per agent `found` (`SIGNED_IN`, `NOT_SIGNED_IN`, `CANNOT_SAY`), its provider and its paths, asked
  of each agent when `Agents` is called, so a picker shows them before anything starts.
- **`importCommand`**, composed by the daemon beside `storeCommand`, for an agent found signed in whose credential the
  vault lacks; no client composes `porelo vault import` itself.
