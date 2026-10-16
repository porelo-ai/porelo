# B149 — A Local Provider Template

**Status:** Estimated.
**Size:** M - http for loopback in two validators, a broker route without a credential across `agents/api`,
`supervisor`, `app-task` and `app-vault`, two provider files and a page.

**What must be true.** Porelo ships two example provider definitions for an OpenAI-compatible endpoint a person runs
themselves: one for a model served on this machine, on the host's loopback, and one for a model on the organisation's
network, over https. So a person can keep prompts on the machine or inside the organisation; choosing and running the
model stays theirs.

## Why

Data sovereignty obligations may rule out a remote provider; Porelo can make the local one easy to use.

**Guideline points it answers:** Federal LLM guidance sheet; DSB ZH. From a review of security guidelines, 2026-10-09.

## The shape

Two provider files under `providers/`: one with the endpoint on the host (`http://127.0.0.1`) and no credential, one
with an https endpoint on the organisation's network; a page that says how a task reaches each.

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
3. `providers/local.yaml`: upstream `http://127.0.0.1`, dialect `openai`, no credential, a comment that the port is
   the server's. `providers/organisation.yaml`: upstream `https://` with a placeholder host on the organisation's
   network, dialect `openai`, and a comment that the host and any certificate authority are the organisation's
   (`TrustedCertificates`). Both packaged with the other provider files.
4. A section in `doc/credentials.md` (or `reach.md`): a model of one's own, the two templates, how a task reaches
   each, and that the prompts stay on the machine, or inside the organisation, only while the server does.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- Both provider files are read by `ProviderDefinition` in a test: the local one with `http` on loopback and no
  credential, the organisation's with `https`.
- The documentation page that describes the area says it.

## To be checked

- Which port and path the templates ship with, given that servers differ (Agent Core's).
- Whether the organisation's template needs a credential at all, or ships with none and says how to add one (Agent
  Core's).
