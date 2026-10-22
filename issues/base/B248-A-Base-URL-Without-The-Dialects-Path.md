# B248 — A Base URL Without The Dialect's Path

**Status:** Backlog.
**Size:** S - one field of the agent API's `AgentProvider`, its reader and JSON form, one line in `CredentialWiring`, their tests and the `provider` block in `agents/README.md`; the agent API only grows.

**What must be true.** An agent definition can say that the agent adds the dialect's path to its base URL itself,
so Porelo hands it the broker's address alone; an agent that says nothing is handed the address with the path, as
today.

## Why

Measured from porelo-agy on Antigravity CLI 1.4.5: given `GOOGLE_GEMINI_BASE_URL=http://127.0.0.1:18080/v1beta`, it
sends `POST /v1beta/v1beta/models/...`, since its client adds `v1beta` itself; Oh My Pi needs the path in the URL.
Read in today's code: `CredentialWiring` sets `base_url_env` to `route.endpointFor(...)`, the address with the
dialect's path, for every agent; the broker forwards the path it receives unchanged (`VaultProxy`), so only the
variable's value differs between the two kinds of agent.

## Plan

Read in `AgentProvider`, `AgentDefinitionReader`, `AgentDefinitionJson`, `CredentialWiring` and `VaultProxy`.

1. **`provider.adds_path`**, a boolean, `false` when absent: the agent adds the dialect's path to its base URL itself.
   `AgentProvider` gains `addsPath`, its earlier constructor kept; `AgentDefinitionReader` reads `adds_path` (refused
   unless a boolean, and only with `base_url_env`, where it means something); `AgentDefinitionJson` writes and reads
   `addsPath`.
2. **`CredentialWiring`** sets `base_url_env` to the broker's address alone (`TaskWiring.VAULT_URL`) when the agent
   adds the path, else to `route.endpointFor(TaskWiring.VAULT_URL)` as today. The broker forwards the path it
   receives (`VaultProxy`), so nothing else changes.
3. **Docs:** the `provider` block in `agents/README.md`.
4. **Red first:** `AgentDefinitionReaderTest.anAgentThatAddsTheDialectsPathItselfIsReadSo`, and a test of
   `CredentialWiring` that the variable holds the bare address for such an agent and the address with the path for
   any other.

## Acceptance

- A definition with `adds_path: true` and `base_url_env: GOOGLE_GEMINI_BASE_URL` gets `http://127.0.0.1:<port>` in
  that variable, and one without it gets `http://127.0.0.1:<port>/v1beta`. Seen to fail first: today both get the
  path.
