# B347 — An Agent Says How It Signs In

**Status:** Review. Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** M - estimated 90 minutes from the median of M builds: a field in the agent API carried through YAML, the
definition's JSON both ways and the agent server, the check in the sign-in, the `BROWSER` setting removed, docs. It
grew from XS when planned: the definition crosses a process boundary, so the field is a contract change.

**What must be true.** An agent's definition says how it signs in: `login.flow` is `paste-code`, `device-code` or
`loopback`. In a session reached over SSH (`SSH_CONNECTION` set), where no browser redirect reaches the account, a
`loopback` flow is refused before it starts, naming the agent, and Porelo never sets `BROWSER`; the interface shows the
hint for the other two. On the person's own desktop a `loopback` flow runs as today. An adapter that does not
declare `login.flow` signs in as today, over SSH too, and `agents --json` answers `"flow": ""`.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Plan

1. `agents/api`: a closed `LoginFlow` enum (`paste-code`, `device-code`, `loopback`) and a field of `AgentDefinition`;
   `AgentDefinitionReader` reads `login.flow` beside `arguments`, `documentation` and `grants` and refuses any other
   value naming the key; `AgentDefinitionJson` writes and reads it, since `AgentServer` describes the definition to
   Porelo and `InstalledAgent` rebuilds it from that answer. A definition without the key keeps today's behaviour.
2. `apps/app-vault` `AgentLogin.login`: with `SSH_CONNECTION` set in `context.environment()`, a `loopback` flow is
   refused before the vault is asked, an image built or a container started, as an outcome of its own naming the
   agent; `VaultLoginCommand` renders it without offering to store a key instead. The image's `ENV BROWSER=...`
   (`browserShim`) goes; the shim that prints the URL stays. The forward advice is replaced by the hint for the flow.
3. `apps/app-agents` `AgentSignIns`: `agents --json` names each agent's `flow`, so the interface shows the hint.
4. Docs: `agents/README.md` (the definition's `login.flow`), `doc/credentials.md` (a sign-in over SSH).
5. Tests, red first: `AgentDefinitionReaderTest` with `login.flow: invalid` - today the key is ignored, so no refusal;
   then the JSON round trip, `AgentLoginTest` (refused over SSH before any container, run without it, no `BROWSER`),
   `AgentSignInsTest`.

## To be checked

- That `BROWSER` is absent inside a sign-in container once Porelo stops setting it, image layers included.
- Which value each adapter declares is measured in its own repository.

## Done

- `login.flow` is read from an agent's definition as `paste-code`, `device-code` or `loopback`, any other value refused
  naming the key; it crosses to the host in the agent's description, an explicit null there being no flow.
- `porelo vault login` refuses a `loopback` sign-in when `SSH_CONNECTION` is set, before the vault is asked, anything
  is built or leftover login containers are swept; an undeclared flow signs in as before, with the forward hint.
- `porelo agents --json` answers `flow`, `""` when undeclared. `BROWSER` is no longer set in the sign-in image.
- No refusal names `porelo vault login` as the way out where it would be refused: a task's start, its check and
  `vault import` ask one rule.
- An agy Gemini reading found the refusals elsewhere, the explicit null, the sweep before the refusal and the docs; all
  fixed, with tests. A narrow reading of those fixes confirmed them and asked for tests that fail if they are undone,
  added for the hint, the sweep and an agent not installed. The three call sites that ask the rule have no test of
  their own: each needs an installed agent and a vault.

## Acceptance

- The three values are read; any other is refused naming the key.
- A `loopback` sign-in with `SSH_CONNECTION` set is refused before it starts, naming the agent; without it, it runs.
- A sign-in over SSH runs with `BROWSER` unset.
- Seen to fail first: today `login.flow` is unknown.
