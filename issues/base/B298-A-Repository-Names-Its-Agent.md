# B298 — A Repository Names Its Agent

**Status:** Review.
**Size:** M - several modules (the project file's reader, the agent choice, the task's workspace, the project
inventory) and one contract change, `Repository` in `Projects` growing by three fields, with the project file's docs.

**What must be true.** A project file can name the agent each repository is worked with, as
`repositories.<repository>.agent`, and give that agent an optional nickname, `repositories.<repository>.nickname`.
The daemon's `Projects` answer carries both on each `Repository`, with the name the agent goes by, so an interface
starts one task per repository with that repository's agent, and shows and addresses it by that name, without asking.
One agent works one repository: a task holds exactly one.

## Why

porelo-frontend [F148](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f148), starting an
agent for each repository at one click, needs to know which agent a repository is worked with. Read in porelo at
0b02c1b7: `ProjectSchema` allows a repository `upstream`, `description`, `egress`, `limits`, `issues` and `image`, and
no agent; `Tasks1`'s `Start` says "a project file names neither an agent nor a provider"; porelo-project
[PJ53](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj53) calls it a new key.

The nickname is the name the agent is addressed by where people and agents talk: the room's display name at
`porelo talk join --display` ([B300](README.md#b300)), whose heads name a member as `Agent <Word>`, and the name its
commits carry.

## Decided

- **One agent per repository.** A task holds one repository (`doc/commands.md`, *Names*), so an agent that spans
  several repositories becomes one per repository; reading across them is the coordinator's.
- **A task's commits carry the name it goes by** as their author, `Agent <nickname> <agent@localhost>` or `Agent
  <repository> <agent@localhost>`, not as a trailer.

## Plan

Read in `ProjectSchema`, `ProjectReader` (`issues`, `:405-450`, as the model for a checked per-repository key),
`Repository`, `AgentChoice.considerAgent` (`:70`) and its callers `StartCheck` (`:922`) and `TaskLaunch`
(`:1140`, `:2171`), `TaskWorkspace` (`:325`, `user.name` from `PORELO_GIT_NAME`, default `agent`), `ProjectInventory`
and `20-project.varlink`'s `Repository`.

1. **The keys.** `ProjectSchema` gains `repositories.<repository>.agent` (string) and `.nickname` (string), both
   optional. `ProjectReader` checks them:
   - `agent` is an agent's name as `porelo agents` lists it: lowercase letters, digits and hyphens. Whether it is
     installed is the start's question, not the reader's, since a followed project is read on machines without it.
   - `nickname` is one word, as a room head names a member: a letter first, then letters, digits or hyphens, at most
     32 characters. So it holds no space, `@`, `:` or control character, never starts with `-` ([B300](README.md#b300)'s refusal of a
     display name read as an option) and fits the transport's 64-character display name with `Agent ` before it.
   - Two repositories of one project may not go by the same name, compared without regard to case. A nickname is
     also refused when it equals another repository's name, since that repository goes by its name when it has no
     nickname. The project's own repository has no entry under `repositories` and goes by `Agent <project>`, which
     a nickname may not repeat either.
   - A nickname without an `agent` is allowed: it names whoever works the repository.
   - `project.nickname` gives the project's own repository's agent its name the same way, so the planning
     repository's agent goes by `Agent Coordinator`.
   - A followed machine needs nothing of its own for them: the follow verifies the signature of the commit that
     holds the whole file (`ConfigurationGate`).
2. **The record.** `Repository` gains `@Nullable String agent` and `@Nullable String nickname`, and `goesBy()`:
   `Agent <nickname>`, or `Agent <repository>` without one (e.g. `Agent porelo-pi`).
3. **The choice.** `AgentChoice.considerAgent` takes the repository's agent as the second source, after `--agent`
   and before what the machine has ([B225](README.md#b225)'s project-wide agent and signed-in agent come after it when built). A named
   agent that is not installed refuses the start and names it and how to install it; `--agent` still wins. The
   choice's source is reported as `REPOSITORY`, so the dry run says where the agent came from.
4. **The commits.** A task's workspace sets `PORELO_GIT_NAME` to `goesBy()`, so a task's commits are authored by
   `Agent Pi` instead of `agent`; the e-mail stays `agent@localhost`. This touches no signature: the task never
   signs, and the gate's push carries the commits unchanged. The shared commit rule is about agents' own
   repositories and their `Assisted-by:` trailer, which this leaves as it is.
5. **The contract.** `Repository` in `20-project.varlink` gains `agent: string` and `nickname: string` (`""` when
   absent) and `goesBy: string`, which the daemon fills so a client derives nothing (`ProjectInventory`). `Tasks1`
   only grows. `Start`'s comment changes: a project file may name a repository's agent.
6. **Docs.** `doc/project-file.md` gains the two keys with an example that `DocumentedProjectFileTest` reads;
   `doc/commands.md` says where `task start` takes its agent from; the changelog.
7. **Red first:** `ProjectRepositoryAgentTest` (a nickname shared by two repositories, one equal to another repository's
   name, one with a space, an `@` or a leading `-` each refused; an agent named and read back), `AgentChoiceTest`
   (the repository's agent chosen without `--agent`, `--agent` winning over it, a named agent not installed
   refused), `StartCheckTest` (`CanStart` asks about the repository's agent as `Start` takes it), `TaskWorkspaceTest`
   (the commit's author is `goesBy()`), and `ProjectInventoryTest` (`goesBy` with and without a nickname); the
   acceptance feature `task-repository-agent` drives the dry run, the listing and a commit on the VM.
8. **Every other place that picks an agent takes the repository's too:** the egress listing (`EgressControl`,
   `shield egress -r`) and `task prepare`, with the `Egress` and `Prepare` contracts; the choice has no overload
   without a repository left, so a new caller cannot leave it out. `shield egress` refuses a repository's agent
   that is not installed in one line with 2, as `task start` does.
9. **Declined after the reading against the skills:** `CanStart` without a repository still answers about the agent
   before the repository (as before this change, and `Start` refuses such a call too); an invalid repository name
   reads with or without the file's name depending on its keys (the same as for `issues`); the positive path with
   the repository's agent installed has no unit test, since no unit test can install an agent, and is driven by
   `task-repository-agent` on the VM; `CredentialChoice` keeps the raw `--agent` in a field nothing reads;
   `task prepare` still prepares an image without an agent when the one asked for is not installed, as its contract
   says, and the daemon's `Prepare` takes no repository; `ConsiderAgentTest` names an agent, as it did before.

## Acceptance

- A project naming `agent: stub` and `nickname: Tester` for a repository: `porelo task start -p <project> -r
  <repository>` without `--agent` starts the stub, and the dry run says the agent came from the repository.
- `porelo project list --json`, which is the daemon's `Projects`, shows `Agent Tester` for it, and `Agent <name>` for a
  repository without a nickname.
- A task's commit on that repository is authored by `Agent Tester <agent@localhost>`.
- Seen to fail first: today a project file with `agent:` under a repository is reported as an unknown key, and its
  agent is not read.
