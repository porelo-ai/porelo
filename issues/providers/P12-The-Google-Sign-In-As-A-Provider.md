# P12 — The Google Sign-In As A Provider

**Status:** Implementing.
**Size:** L - a provider file; the agent API lets one agent speak a second dialect under its own base URL variable;
a sign-in's renewal carries a client secret; tests at each place, a doc row and the accepted risk. The agent side is
porelo-agy's ([AG01](https://github.com/porelo-ai/porelo-agy/blob/main/issues/README.md#ag01)).

**What must be true.** A task's Antigravity CLI is served models through the person's Google sign-in, brokered on
the host like the other sign-ins, with the real tokens never in the container. It is a provider of its own beside
the API key of [P06](README.md#p06), as [P11](README.md#p11) is beside [P05](README.md#p05).

## Why

Measured by porelo-claude-code's agent on Antigravity CLI 1.4.5, without a sign-in: the API-key path can be pointed
at a local endpoint, the signed-in path cannot be measured without one. Read in the binary, not measured: the
signed-in path takes its endpoint from `CLOUD_CODE_URL`, and the sign-in is kept in the desktop keyring. Google's
terms call third-party access with Antigravity's sign-in a breach that may suspend the account; `doc/credentials.md`
says so.

## Measured

By Agent AGY on Antigravity CLI 1.4.5, attended, on the VM (2026-10-22):

- **The sign-in is kept** in `~/.gemini/antigravity-cli/antigravity-oauth-token` (mode 0600) when no desktop keyring
  is reachable, as in a container: `auth_method: consumer`, an optional `id_token`, and `token` with `access_token`,
  `token_type: Bearer`, `refresh_token`, `expiry` (RFC 3339) and `expires_in`.
- **The model route** is `https://daily-cloudcode-pa.googleapis.com`, `POST /v1internal:*` (`loadCodeAssist`,
  `fetchAvailableModels`, `streamGenerateContent?alt=sse`, among others), with `Authorization: Bearer`.
- **`CLOUD_CODE_URL` moves the whole signed-in path**, over plain http: with a token file holding only a phantom
  access token and a far `expiry`, every `/v1internal:*` request reached a relay, and with the real bearer swapped in
  upstream the turn succeeded.
- **Three hosts lie outside it:** `www.googleapis.com` (`/oauth2/v2/userinfo`), `play.googleapis.com` (telemetry) and
  `antigravity-unleash.goog` (feature flags). A reachable `userinfo` answers the phantom with 401, which the CLI
  takes as fatal; a refused one is a network error it goes on past.
- **The refresh** is `POST https://oauth2.googleapis.com/token`, `grant_type=refresh_token`, with Antigravity's own
  installed-app client: a client id and a client secret compiled into the CLI.

## Plan

Read in today's code: an agent declares one `provider` block, so one dialect and one `base_url_env`
(`AgentProvider`); `ProviderRoute.of` takes the path for that dialect; a sign-in is renewed on the host by
`LoginRenewal`, which passes no client secret (`TokenPurchase.Refresh(..., null, ...)`), while `TokenPurchase` sends
one when it has it.

1. **`providers/google-sign-in.yaml`:** `upstream: https://daily-cloudcode-pa.googleapis.com`, `dialects: {
   cloud-code: "" }`, `Authorization: Bearer`, the API key `unbrokerable` naming `google`. `google.yaml` names the
   sign-in as unbrokerable in turn.
2. **An agent may speak a second dialect**, each under its own variable: a new optional key beside today's
   `provider` block, `provider.dialects`, dialect to `{ base_url_env, adds_path }`. The block's own `dialect` stays the
   default. `SelectedProvider` picks the dialect the chosen provider serves among those the agent speaks, and
   `ProviderRoute.of` and `CredentialWiring` take that dialect's variable and path. An optional field: no protocol
   version raise. One agent speaking two dialects is the case it is for; a dialect that two of its variables would
   both claim is refused when the definition is read.
3. **A sign-in's renewal carries the client secret its agent names**, kept in the vault beside the refresh token as
   one more hidden entry, never a setting, so `vault show` and the container never see it.
4. **Refused in the task, on agy's side:** the three hosts above, listed by `porelo agents --verbose`.
5. **Docs:** a row in `doc/credentials.md`'s providers; the terms' risk, already named there, as an accepted risk in
   `doc/decisions.md`.
6. **Red first:** `SelectedProviderTest` that `google-sign-in` is shipped and an agent speaking both dialects is
   routed to the sign-in's under `CLOUD_CODE_URL` and to the key's under its own variable; a `LoginRenewalTest` that
   the refresh form carries the client secret and that `vault show` does not.
7. **The agent side, porelo-agy:** the definition adds `cloud-code` under `CLOUD_CODE_URL`; its login collects
   `antigravity-oauth-token`; its adapter turns it into an `oauth` credential (access token as the secret;
   `refreshToken`, `expiresAt` from `expiry`, `tokenUrl`, `clientId`, the client secret); its setup writes a token
   file holding only the phantom and a far `expiry`.

**Decided:** **Antigravity's client secret is read from the installed, digest-pinned CLI at
sign-in** and kept only in the vault, as a hidden entry beside the refresh token. It is never written into
porelo-agy's source, a setting, a log or a post: it is another vendor's credential, and republishing it would add to
the terms' risk.

**Found in the live walk, and fixed:** a due sign-in was refused at its first renewal as `invalid_client`.
Antigravity's CLI holds three OAuth clients and chooses one by the kind of sign-in in its code; the adapter stored
the first id and the first secret it found, a pair Google does not know. The adapter now hands every id and every
secret (`Credential.CLIENT_ID_CANDIDATES`, `CLIENT_SECRET_CANDIDATES`), and the host's login (`ClientPick`) tries
each pair once with the fresh refresh token and keeps the one the service answers. That a refused pair leaves the
refresh token usable is what the next live walk's renewal shows. A reading by a second agent changed the pick: a
refresh token is sent only to an endpoint `Grants.secure` allows; only `invalid_client`, `unauthorized_client` and
`invalid_grant` mean the next pair, any other answer stops it; and no transport's message reaches a refusal, which
names codes and counts only.

## Acceptance

- A task's Antigravity CLI answers through the person's sign-in, and the container holds none of its tokens nor the
  client secret, seen by searching its environment, files and process arguments. Seen to fail: a request forwarded
  with the phantom bearer.
- A sign-in whose access token has expired is renewed on the host, with the client secret, and the task's next turn
  answers.
- The three hosts are refused, and the turn still answers.
- An API key stored for `google-sign-in`, or a sign-in for `google`, is refused before the start, naming the other.

## To be checked

- How the sign-in is made in a login container: a URL to open and a code to paste back, or a loopback redirect the
  container cannot receive.
- Whether Google rotates the refresh token on this client; the renewal stores a rotated one either way.
