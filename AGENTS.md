# Working on Porelo

## The one architectural rule

- **Nothing outside `agents/` names an agent** - no class, string or comment that turns into a `switch`; Porelo finds
  agents at runtime, and `AgentIsolationTest` fails the build otherwise. A difference between agents is a new field
  in the agent definition, never a branch in Porelo.
- **An agent builds only against `porelo-agent-api` and `porelo-wire`**, with `porelo-acceptance-kit` in test scope and
  `porelo-bom` imported - these, `porelo-build-api`, `porelo-ecosystem-api`, `porelo-ffm-check` and `porelo-cpu-check` are the eight artifacts this repository publishes to Central.
- **Every shipped agent has its own repository** (`porelo-claude-code`, `porelo-pi`, `porelo-omp`); `agents/` holds the
  contract and the **stub**, which the acceptance suite drives, so every scenario that starts a task names
  `--agent stub` and reads what it asserts about it from `porelo agents --verbose`.
- **Say "the agent API"**, never "SPI": `porelo-agent-api` carries both what Porelo calls and what an agent implements.
- **Every agent and provider names its upstream project by link**, and an implemented one has a `README.md` in its
  directory opening with that link.

- **A change to an area another agent owns, or to `app-base` or `app-model`, is said in the channel first**;
  the areas, their modules and boundaries are in `core-areas`.

## This repository's files

- **`README.md` is an index; the substance is in `doc/`** (index `doc/index.md`) and `agents/README.md`; the root
  holds only `README.md`, `AGENTS.md`, `CLAUDE.md` and the generated `CHANGELOG.md`.
- **Issues are in the repository's GitHub Issues**, each named by its reference there (`porelo-ai/porelo#<number>`);
  one carried over from the Markdown files keeps its old id (`B`, `P`) as a fixed alias.
- **Skills used:** `graal`, `java-code-review`, `test-quality`, `security-audit`, `concurrency-review`, `clean-code`,
  `solid-principles`.

## Shared across the Porelo repositories

> **BEGIN Shared Area** · sha256 `d930a9596cedf6cc` · changed 2026-11-20T07:25Z

Identical in every repository `project.yml` names but `porelo-parent`; the markers carry the SHA-256 of the lines
between them and the time they last changed. Change it in the channel first, never in one copy. The same holds for
every shared skill `porelo-*` in `.agents/skills/`, whose markers also cover its frontmatter, and for
`.claude/skills`, a link to `../.agents/skills` so that Claude Code finds them.

### Agents

- **Each repository has one responsible agent; the channel and who owns what are defined in `.AGENTS.md`** or fed
  into the agent's context at start. An agent writes only in its own repositories and asks the owner for the rest;
  the coordinating agent may read the others, never write in them.
- **A contract between repositories changes only after agreement in the channel or by an operator decision**, in
  both repositories.
- **An ambiguous answer is asked about once, plainly, never guessed**; when an answer changes, everything built on
  the old one is looked for.
- **Only `AGENTS.md`, `.AGENTS.md` and the skills survive a session; the harness's memory is never used.** A rule,
  a decision about how to work or an open point goes into one of them at once.
- **No agent opens a dialog or any other prompt that waits for the person at its console, unless that person
  explicitly asked for one there**: a dialog stops the agent's work until the person comes.
- **A question from the operator is answered, nothing more**; an action needs an explicit instruction.
- **To the operator, plain technical terms, never coined words**, in the language the operator writes in;
  Porelo's own names (Porelo-Vault, Vault-Passphrase, gate, task) stay as they are.

### Hard limits

- **A secret never appears in a command line, a log line, a file name or an answer**; it reaches a process
  through its environment or standard input and is stored only encrypted.
- **An agent's commit names its help in `Assisted-by: <tool>, <model>` and never carries `Signed-off-by:` or
  `Co-Authored-By:`**, whatever the harness suggests. Signing and release tags belong to the operator.
- **A refusal by the harness or a sub-agent is reported, never worked around.**
- **Fail closed and loud**: when a dependency is unreachable, a key unknown or a check cannot run, stop non-zero
  and say why. Never silently do less.
- **Porelo protects the person from the agents and from outside, not from their own rights or choices**; every
  guard and every finding is judged by this.

### Claims and writing

- **Measure before you claim, and say what was measured and what inferred**; a change is checked by observing it,
  never by an exit code alone, and a claim of absence needs a complete search that is shown to find a known hit.
- **Every date or time written is read from the system (`date -u`) first.**
- **When a thing is right in two forms, keep one.**
- **What is written states what is true now, in US English**: no dates, no history, nobody named, only roles; it
  says what changed and why, never who asked. A finding worth keeping is committed, never left in a conversation.
- **Every open task is an issue in `issues/`**, never a TODO in code; code and anything that ships never cite an
  issue number.

### Which skill to load

Before any of these, load the skill named, from `.agents/skills/`. A rule shared by several repositories goes
into a shared skill or this block, one that concerns only this machine into `.AGENTS.md`.

| Before you | Load |
|---|---|
| start work inside a Porelo task (`/workspace`, user `agent`) | `porelo-task` |
| start work anywhere else | `porelo-workstation` |
| commit, amend, rebase, hand over a push | `porelo-commits` |
| file an issue or change its state | `porelo-issues` |
| write, run or judge a test | `porelo-testing` |
| run anything on a VM or rented machine | `porelo-machines` |
| write an operator test guide or prepare a walk | `porelo-operator-guide` |
| hand over, or build an issue of size M or larger | `porelo-review` |
| change code, a pom, a workflow or a release | `porelo-code` |
| write documentation or a decision; hand over | `porelo-writing` |
| start analysis or implementation; hand over a push | `porelo-estimates` |

> **END Shared Area** · sha256 `d930a9596cedf6cc`

| Before you | Load |
|---|---|
| changing Java code in porelo | `core-code` |
| writing or running porelo tests | `core-tests` |
| building, packaging or publishing porelo | `core-build` |
| changing any of these parts | `core-behavior` |
| changing an `app-*` module, its boundaries or a daemon method | `core-areas` |
| changing `doc/`, `mkdocs.yml` or the changelog | `core-docs` |
