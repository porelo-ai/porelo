# Ways of working

A way of working is a [security class](security.md), a model provider and a Git destination chosen together. The class
decides what leaves a task's container; the model provider still receives the prompts whatever the class. In
`project.yml`, `upstream` names the immediate Git destination: the host's forge or a central or company forge.

| Way | Class | Model provider | Git destination | For | Advice |
|---|---|---|---|---|---|
| Highest security | `offline` | a model on this machine (an organisation's network keeps prompts in the organisation, not on the machine) | none: history in and work out as a bundle carried by hand | confidential code, regulated settings | only tight with a model on this machine; a cloud provider still sees the prompts |
| Straight to the central forge | `guarded` | cloud or local | GitHub or a company forge | one person, a few agents, no porelo-host | the gate's approval is a code review; the first independent build runs on the forge after it |
| With the host's own forge | `online` | cloud or local | the host's Forgejo (porelo-host) | many agents, builds before any approval | Porelo's own way; see porelo-host's [`doc/local-forge.md`](https://github.com/porelo-ai/porelo-host/blob/main/doc/local-forge.md) |
| Against a private company forge | `online` | cloud or local | a private company forge with its own review rules | teams with a forge of their own | the company's merge rules are the approval |
| Not to use | `online` | - | a public repository | - | unreviewed work becomes public at once |
| Not useful | `guarded` | - | the host's forge | - | a third approval with no protection gained |

The interface and the IntelliJ plugin are clients of [the daemon](running.md#the-daemon), which serves what waits at a
gate, its review, its approval and its rejection; every step below is a CLI command.

## Highest security

`offline` gives the code no route: no external host resolves, and no Git work leaves on its own. The agent's model
calls still go through the host's broker to its model provider, so the class is only tight with a model on this
machine; a cloud provider still sees the prompts. A model in the organization's network keeps them in the
organization, not on the machine.

1. Follow the project from a file: `porelo project follow <project> <file>`, a bundle of its repository or a directory
   on this machine. A mirror is seeded from a carried bundle with `porelo gate restore <file> --project <project>`.
2. Start a task: `porelo task start --project <project> --repository <repository>`. The agent clones from the gate's
   Git mirror, builds and tests in its container, and pushes back to the mirror.
3. Read the work: `porelo gate review -p <project> -r <repository> <task>`, or open it as a copy with
   `porelo gate checkout <task> -p <project> -r <repository>`.
4. Carry it out: `porelo gate backup <file> --project <project>` writes the whole mirror, pending pushes included, as
   one bundle. Gate approval refuses in `offline`.

- **The person decides** what history comes in and what work is carried out.
- **The first independent build** is the person's: in a copy opened with `gate checkout`, or wherever the bundle is
  carried.
- **It needs** a project file with no `upstream` and no external egress, an agent, and a model provider. Porelo ships
  provider files for cloud providers only; a model on this machine or in the organization's network needs
  [a provider file of your own](credentials.md#model-providers).

## Straight to the central forge

A `guarded` project's gate holds every push of the agent until a person approves it, and the approval forwards it to
the repository's `upstream` on GitHub or a company forge.

1. Follow the project: `porelo project follow <project> <url-or-path> --signed-by "ssh-ed25519 AAAA..."`, its file
   saying `security_class: "guarded"` and the repository's `upstream`.
2. Start a task: `porelo task start --project <project> --repository <repository>`. The agent builds and tests in its
   container and pushes to the gate.
3. Read the work: `porelo gate review -p <project> -r <repository> <task>`.
4. Forward it, or drop it: `porelo gate approve -p <project> -r <repository> <task>` pushes it onto `main`, or the
   branch `--branch` names; `porelo gate reject` drops it and tells the task.

- **The person decides** whether the code goes to the forge. The gate's approval is a review of the code.
- **The first independent build** runs on the forge after the approval, if the repository's workflow builds that push.
- **It needs** the followed project, an agent, a model provider credential, and the host's credential for the forge
  ([what this machine connects out with](credentials.md#what-this-machine-connects-out-with)).

## With the host's own forge

Porelo's own way: an `online` project whose repositories name the host's Forgejo as `upstream`. The forge itself - its
setup, registry, runner, credentials and what is lost with the host - is described in porelo-host's
[local forge](https://github.com/porelo-ai/porelo-host/blob/main/doc/local-forge.md).

1. Follow an `online` project whose repositories name the host's forge as `upstream`, with `builds:` naming the
   `forgejo` build reader and its credential in the vault.
2. Start a task: `porelo task start --project <project> --repository <repository>`. The gate passes each push on to
   `porelo/<task>` on the forge at once.
3. Review the task's branch and its build in a pull request in Forgejo, and merge it into the forge's `main`.
4. Send the merged work to the central forge, the gathered push; its tool is not built yet.

- **The person decides** twice: the merge on the host's forge, and the gathered push to the central forge.
- **The first independent build** runs on the host's forge runner, before any approval, if the repository's workflow
  builds task-branch pushes or pull requests.
- **It needs** porelo-host's forge and runner, an agent, a model provider credential, and the `forgejo` build reader.
  A model provider in the cloud still sees the prompts; a Git destination on this host does not change that.
- **Not built yet:** main's protection set by the forge role (set it in Forgejo yourself), the reviewer start, comments
  reaching the task and waking it, the task's end after the merge, the gathered-push tool, and the automatic merge
  task.

## Against a private company forge

An `online` project passes the task's branch on at once, so the company forge's merge rules are the approval. The
repository must be private, and the forge must enforce the team's review.

1. Follow an `online` project whose repository's `upstream` names the company forge, with `builds:` naming a build
   reader for it if Porelo is to read its builds.
2. Start a task: `porelo task start --project <project> --repository <repository>`. Each push reaches `porelo/<task>`
   on that forge at once.
3. Review the task's branch and its build on the forge, and merge it by the company's rules.

- **The person decides** the merge, under the company's rules.
- **The first independent build** runs on the company forge, if its workflow builds the task's push or pull request.
- **It needs** the followed project, an agent, a model provider credential, the host's credential for the forge, and
  for build reading a reader and its API credential.
- **Not built yet:** comments reaching the task and waking it.

## Without any forge

In a local checkout that no followed project names, `porelo task start` uses the built-in `guarded` project `default`,
and no project file or forge is involved. In the same checkout, `porelo approve` shows the waiting work, asks, and
brings it in as the branch `porelo/<task>`; merging that branch is the person's. See
[getting started](getting-started.md#7-review-and-approve).

## Not to use, and not useful

- **Not to use:** `online` against a public repository makes unreviewed work public at once.
- **Not useful:** `guarded` against the host's forge holds the branch before the forge can build it, and adds a third
  approval before the merge and the gathered push, with no protection gained.

## How gate review and forge builds fit together

The agent builds and tests in its own task; the forge's build checks the work apart from the agent. A review at the
gate is a review of the code: `porelo gate review` names dangerous files - a CI definition, a build script, a
dependency manifest - first, and `porelo gate checkout` opens the work as a copy to read. Porelo runs no build of its
own on waiting work.

Porelo asks the forge through a [build reader](build-readers.md) on the host, so the forge may be on this machine or
another. What a task sees of it is in
[being told what the build did](running.md#being-told-what-the-build-of-a-push-did).

## Three kinds of AI coding tool

Tools for working with AI coding agents center on one of three things:

| Center | Metaphor | What it optimizes for | Where it falls short |
|---|---|---|---|
| **The project** | The factory | Autonomous cloud runs from an issue to a pull request | Review bottleneck; no view across several repositories on your own machine |
| **The agent** | The cockpit | Watching and steering several agents live in a terminal or editor | The person's attention is the bottleneck; agents run with the developer's own credentials and network |
| **The person** | The control tower | Letting agents work unattended inside hard boundaries per project, with explicit decisions at the gate or on the forge | Requires container isolation and declared project rules |

Porelo is built around the third: each task runs in its own container with a phantom token for its model provider,
reaches only the hosts the project declared, and pushes its commits to the gate. The project's security class decides
whether the gate holds the work for approval or passes the task's branch on at once.
