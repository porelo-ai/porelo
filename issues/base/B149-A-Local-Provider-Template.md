# B149 — A Local Provider Template

**Status:** Analyzed.
**Size:** M - http for loopback in two validators, a broker route without a credential across `agents/api`,
`supervisor`, `app-task` and `app-vault`, a provider file and a page.

**What must be true.** Sokar ships an example provider definition for a model served on this machine, an OpenAI-compatible endpoint on the host, so a person can keep prompts on the machine; choosing and running the model stays theirs.

## Why

Data sovereignty obligations may rule out a remote provider; Sokar can make the local one easy to use.

**Guideline points it answers:** Federal LLM guidance sheet; DSB ZH. From a review of security guidelines, 2026-10-09.

## The shape

A provider file under `providers/` with the endpoint on the host and no credential; a page that says how a task reaches it.

## Plan

**Today (read 2026-10-11).** `ProviderDefinition` and `ProviderRoute` (`agents/api`) refuse any upstream that is not
`https` with a host. A provider without `token_env` gets no broker: `CredentialWiring.agentRoute` says "nothing is
brokered for it; it will not authenticate" and returns nothing, so the agent is pointed at no endpoint.

**How a task reaches a host port while its firewall allows none:** through the broker. The broker runs on the host,
with the host's network, and the agent reaches it through the relay in its own namespace (`vault relay`,
`TaskWiring.VAULT_URL`). A model on the host's loopback is reached by the broker; the task's firewall stays closed.

**Steps:**

1. `ProviderDefinition` and `ProviderRoute`: `http` allowed when the host is loopback (`127.0.0.0/8`, `::1`,
   `localhost`), `https` required everywhere else. A loopback upstream needs no deny rule: the container's loopback is
   its own. Red first: `ProviderDefinitionTest` accepting `http://127.0.0.1:11434` and refusing `http://example.org`.
2. A broker route without a credential: a provider that declares none still gets a broker; `VaultProxy` adds no
   credential header and still checks the phantom token on its socket; `CredentialWiring`, `CredentialChoice` and
   `StartCheck` do not ask the vault for one. Red: a `VaultProxy` test whose stand-in local server receives the
   agent's request with no `Authorization` header.
3. `providers/local.yaml`: upstream on `127.0.0.1`, dialect `openai`, a comment that the port is the server's;
   packaged with the other provider files.
4. A section in `doc/credentials.md` (or `reach.md`): a local model, how a task reaches it, and that the prompts stay
   on the machine only while the server does.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## Open questions

- Whether the template is for a model on this machine only, or also one on the organisation's network over https
  (the operator's).

## To be checked

- Which port and path the template ships with, given that servers differ (Agent Core's).
