# P11 — Provider ChatGPT Sign-In

**Status:** Backlog.
**Size:** M - a provider file, one new provider key the broker and the agent API carry, the sign-in kept whole with
one more setting, tests at each place, a doc row; the agent side is porelo-codex's.

**What must be true.** A task's Codex CLI is served models through the person's ChatGPT sign-in, brokered on the
host like the supported sign-ins, with the real tokens never in the container. It is a provider of its own beside the
API key of [P05](README.md#p05).

## Why

Taken over from porelo-project [PJ54](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj54),
measured in porelo-codex [CX01](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx01) with the
CLI in a rootless container and a relay on the host:

- **The credential** is an OAuth sign-in made through a device-code flow: `access_token`, `account_id`, `id_token`
  and `refresh_token`.
- **The model route** is `chatgpt.com`, `POST /backend-api/codex/responses`. The broker replaces the client's dummy
  bearer with the access token and adds the account id as `ChatGPT-Account-Id`.
- **The refresh** is `POST auth.openai.com/oauth/token` with `grant_type=refresh_token`, and it rotates the refresh
  token, so the new one is stored before it is used.
- **The CLI also contacts `ab.chatgpt.com`** at start-up, measured with a signed-in profile; a client pointed at the
  relay with a dummy token reached the model route without a separate session request.

**The accepted risk.** The route is the subscription's own backend, not a documented API: it can change without
notice, and whether the provider's terms allow it was not checked. It stays because the CLI is mostly used with that
subscription; a change of the route or a refusal by the provider would change the answer.

## Plan

Read in today's code: a sign-in is already kept whole and renewed on the host (`LoginCredentials`, `LoginRenewal`,
`TokenPurchase.Refresh`), and the renewal stores a rotated refresh token and the new access token in one vault update
under its lock before the token is handed to a request. What is missing is a header whose value is not the token.

1. **Red first:** `SelectedProviderTest` that `chatgpt` is shipped, serves `openai` under `/backend-api/codex`, takes
   `oauth` in `Authorization: Bearer` and refuses an API key before the start; a `VaultProxyTest` that a request on a
   `chatgpt` route arrives upstream with `ChatGPT-Account-Id` from the vault entry and without the container's own
   value of that header; a `LoginCredentialsTest` that `account_id` from the agent's credential is kept as a setting.
2. **`providers/chatgpt.yaml`:** `upstream: https://chatgpt.com`, `dialects: { openai: "/backend-api/codex" }`,
   `auth_header`/`auth_prefix` `Authorization` / `Bearer `, `token_env: { _default: CHATGPT_TOKEN }`,
   `unbrokerable: { api-key: ... }` naming `openai`, and the new key below.
3. **A new provider key, `credential_headers`:** header name to the setting of the vault entry it is filled from,
   here `ChatGPT-Account-Id: account_id`. Read by `ProviderDefinitionReader` into `ProviderDefinition`, carried by
   `ProviderRoute` and `VaultProxy.Route`; the broker sets each header from the entry and drops the container's own
   value of it. An entry lacking a named setting is refused before the start (`StartCheck`), never sent without it.
4. **`LoginCredentials.put`** keeps the credential attributes the provider's `credential_headers` name as settings
   beside `expires_at`, `token_url` and `client_id`; none is a secret.
5. **`doc/credentials.md`:** a row in "The providers Porelo ships", and the accepted risk in `doc/decisions.md`.
6. **The agent side, porelo-codex:** its definition takes `provider: { default: chatgpt, dialect: openai, endpoint:
   url }`, its login stores what the device-code sign-in leaves, and its adapter turns that into a `Credential` of
   type `oauth`: the access token as the secret, and `refreshToken`, `expiresAt`, `tokenUrl`
   (`https://auth.openai.com/oauth/token`), `clientId` and `account_id` as attributes.

## Acceptance

- A task's Codex CLI answers through the person's ChatGPT sign-in, and the container holds none of its tokens, seen
  by searching the container's environment, files and process arguments for them. Seen to fail: a request the
  broker forwards without `ChatGPT-Account-Id`.
- A rotated refresh token is kept when the broker is interrupted after the refresh. Seen to fail: a vault that still
  holds the spent refresh token after a renewal.
- `ab.chatgpt.com` is refused: it is a refused domain of porelo-codex's definition, listed by `porelo agents
  --verbose`, and a connection to it from the task is dropped by the firewall. It is not the model route, and a client
  pointed at the broker reached the model without it.
- An API key stored for `chatgpt`, or a sign-in stored for `openai`, is refused before the start with a reason naming
  the other provider.

## To be checked

- Whether the CLI pointed at the broker with a dummy token contacts `ab.chatgpt.com` at all; Agent Codex measures it
  in the task.
- Whether an existing test already interrupts a renewal between the token endpoint's answer and the vault update;
  if not, it is written here.
- The CLI's client id and how the access token's end is read (its JWT `exp`), from Agent Codex.
