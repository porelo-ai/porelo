# B178 — A Credential With No Expiry Said So

```json
{
  "$schema": "issue-1.json",
  "id": "B178",
  "title": "A Credential With No Expiry Said So",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 15,
  "blockedBy": [],
  "covers": "Storing a credential with no end says so (`vault put`, `vault login`, `ImportCredential`), and `vault list` and `Credentials()` mark it with `noExpiry`.",
  "openQuestions": 0
}
```

**Why this size.** one rule in `app-model` read by three ways of storing and two lists, a field added to `Credential` in
`30-vault.varlink` (a contract the interface reads), and `doc/credentials.md`; small for an M. Estimate: 63 minutes
(Core's median for M), p80 229. The analysis below was read again in today's code and holds; one correction is in
step 1.

**What must be true.** Storing a credential that carries no end - by `vault put`, `vault login` or `ImportCredential` -
says so when it is stored, rather than refusing it, since some providers issue nothing else. The credential list
(`vault list`, `Credentials()`) marks such an entry, so the one long-lived secret left stays visible to whoever can
replace it with a shorter-lived one. This is the decision "A credential with no expiry is stored and named" in
`doc/decisions.md`, not yet built.

**Decided 2026-10-18: the mark covers every entry without an end**, ssh keys and forge tokens as well as provider keys:
a leaked ssh key or forge token does not decay either, and the decision names no kind.

## Plan

Read in today's code. An entry is a `VaultEntry` (value, type, settings). What ends is recorded only as the setting
`expires_at` (`LoginCredentials.EXPIRES_AT`), written by `LoginCredentials.put` for `vault login` and `vault import`
when the agent's sign-in names an end, and moved forward by `LoginRenewal`. A grant entry (`oauth-device`,
`oauth-code`, `Grants.isGrant`) keeps what was granted hidden as `grant/<name>`: a refresh token (`refresh-token`), or
an access token that never expires (`VaultAuthorizeCommand.HELD`, GitHub's device flow). `vault put` stores the value
with whatever `--setting` it is given, `expires_at` unchecked. Nothing says "no expiry" today: `vault list` prints
name, type, length and settings; `VaultMethods` answers `Credential` with the same.

1. **One rule.** A public `CredentialLifetime.endless(VaultEntry entry, @Nullable VaultEntry grant)` in `app-model`,
   beside `LoginCredentials`: a grant entry has no end when its grant is `HELD`, and none to judge while nobody has
   granted it; any other entry has no end when it has no `expires_at`. A renewable sign-in keeps `expires_at`, so it
   is never marked; an imported one that ends is not marked either. `app-model` cannot read `app-vault`, where
   `VaultAuthorizeCommand.HELD` is, so the type name `access-token` moves into `CredentialLifetime` and `HELD`
   refers to it.
2. **Said when stored.**
   - `VaultPutCommand`: after `stored ...`, a line `expires   never - nothing ends this credential; if the provider
     issues one that ends, store that instead, or record its end with --setting expires_at=<instant>`. An
     `expires_at` given by `--setting` that is not an ISO-8601 instant is refused (exit 2), since a typo would
     silently count as an end.
   - `AgentLogin` (`vault login`): `Result.detail` carries the same sentence on `STORED`, which
     `VaultLoginCommand` prints after "Signed in".
   - `CredentialImport` (`vault import`, `ImportCredential`): the same sentence joins `detail`, which the contract
     already describes as "a warning about what was stored"; `VaultImportCommand` prints it.
   - The exit code stays 0 and the outcome `STORED`/`IMPORTED`: it is a statement, not a refusal.
3. **Marked in the list.** `VaultListCommand` prints `no expiry` in place of nothing after the length, and
   `VaultMethods.credentials` adds `noExpiry: bool` to each row. In `30-vault.varlink`, `type Credential` gains
   `noExpiry: bool` with a comment: true when nothing Porelo holds ends the secret it attaches, so a leak of it does not
   decay; an added field, so `Tasks1` only grows. A bool rather than an empty `expires`: an empty string would also be
   what a grant nobody has granted yet answers.
4. **Docs:** `doc/credentials.md` says which entries are marked, what the line at storing says, and that
   `--setting expires_at=<instant>` records an end a provider gave a key; the contract's comment.

## Red-first test

`VaultPutCommandTest` - storing a key with no settings prints the no-expiry line (today it does not);
then the rule's own test over the five cases (plain key, `expires_at`, renewable sign-in, `HELD` grant, refresh-token
grant), a `PoreloDaemonTest` case that `Credentials()` answers `noExpiry` true and false, and
`InterfaceDescriptionTest` holding the field.

## Risks

- A person who stores many keys sees the line each time. It is one line, and it names the way to record an end.
- Refusing a malformed `expires_at` changes `vault put` for a script that passes one today. Such a value counted as
  an end it was not, so the refusal is the point; `doc/credentials.md` says it.
- `noExpiry` is a field added to a record that crosses varlink: two edits, and `InterfaceDescriptionTest` holds it.

## Blockers

None.

## Acceptance

- `vault put` of a key with no end prints the no-expiry line and exits 0; with `--setting expires_at=<instant>` it
  does not; with a malformed `expires_at` it is refused. Each seen to fail first.
- `vault login` and `ImportCredential` of a credential with no end say so in what they print and in `detail`; of one
  with an end, they do not.
- `vault list` and `Credentials()` mark exactly the entries the rule names: a plain key and a `HELD` grant, never a
  renewable sign-in, an entry with `expires_at`, or a refresh-token grant; seen to fail first as no mark at all.
- In `vault-commands.feature`, a key stored with `vault put` is listed with `no expiry`.
- `doc/credentials.md` says it.

## Open questions

None.
