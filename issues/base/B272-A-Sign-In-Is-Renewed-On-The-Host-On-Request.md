# B272 — A Sign-In Is Renewed On The Host On Request

**Status:** Analyzed.
**Size:** S - one vault command in `app-vault` that changes one setting under the vault's lock, its tests and two
document rows; no contract, no change to the broker.

**What must be true.** A person can have one of an agent's own sign-ins renewed with one command,
`porelo vault renew <name>`. It marks the sign-in due, so the next task's broker renews it on its first request, by the
same path every renewal takes. Nothing but its end changes, and no token is printed. A credential that is not Porelo's
own sign-in (an import, an API key, a token) is refused, saying why.

## Why

Measured on 2026-10-24 while preparing the live check of the ChatGPT sign-in for Codex: there is no way to see the
host's renewal work except to wait for the access token's end. The broker renews only within 60 seconds of it
(`TokenPurchase.MARGIN`), and a ChatGPT sign-in ends ten days after it was made.

Read in the code: `porelo vault put` is no way around it. It takes the secret on standard input and replaces the whole
entry, so a person would have to handle the access token by hand to change its `expires_at`. The settings cannot be
changed on their own.

So a sign-in whose renewal is broken is found only when it ends, in the middle of a task, ten days after the person
signed in.

## Decided

- **Mark due, not renew now** (operator, 2026-10-24): the next brokered request renews through `LoginRenewal.renew`,
  the one path tasks use. A command that renewed itself would test a second caller of the same code, not the broker.
- **What "due" is:** the entry's `expires_at` set to the present instant, inside one `VaultFile.update`. Its value, type
  and other settings stay, and so do the refresh token and the client secret kept hidden beside it.
  `LoginRenewal.goodUntil` then lies a minute in the past, so the first `current()` renews.
- **Who renews it:** the broker of a task started after the command. Read in the code: a running broker keeps the token
  in memory (`LoginRenewal.token`, `good`) and reads the vault again only when that runs out, so a running task goes on
  with its token, which is still good, until its own end. The command says so in one line. Changing the broker so that
  it rereads would add a vault read to every request; the test does not need it.
- **What is refused,** each saying why, exit 2:
  - a name the vault does not hold;
  - an entry that is not renewable (`LoginCredentials.renewable` false), such as an API key or a token;
  - a renewable entry with no refresh token beside it, an imported sign-in: "imported from this machine's own agent;
    renewing it would end that agent's session; sign in with `porelo vault login` to have it renewed here".
- **What it prints:** `marked 'chatgpt' due: the next task's first request renews it on the host; a running task keeps
  its token until it ends`. `porelo vault list` then shows the `expires_at` it set, and after the renewal the new end.
  Nothing is printed of the token, the refresh token or the client secret.
- **The broker says when it renewed.** Read in the code: today `LoginRenewal` renews silently, so nothing shows that
  the renewal happened, only `expires_at` moving. It prints one line to the broker's standard error, beside its other
  warnings: `porelo: renewed the sign-in for 'chatgpt' on the host; it now ends <instant>`, and on a failed renewal
  the reason it already gives. It prints no value.
- **Not in `Tasks1`:** the command opens the vault like `vault put` does. A method can follow when Frontend needs one.

## Plan

- **`VaultRenewCommand`** in `apps/app-vault`, registered in `VaultCommand`, `@Parameters` `<name>`. It unlocks
  through the context as `vault put` does, then `VaultFile.update`: read the entry and its `GRANT_PREFIX` entry, refuse
  as above, else put the entry back with `expires_at` = `Instant.now(clock)`, keeping everything else.
- **Tests, red first** (`VaultRenewCommandTest` in `apps/app`):
  - a ChatGPT-shaped sign-in with a refresh token and a client secret is marked due, and value, type, the other
    settings, the refresh token and the client secret are unchanged byte for byte;
  - an import, an API key and a missing name are refused with their reasons, and the vault is unchanged;
  - nothing secret in the output: the test's token, refresh token and client secret appear in neither stream;
  - **the broker's path:** a `LoginRenewal` built from the marked entry, against `LoginRenewalTest`'s stand-in service,
    renews at its first `current()` exactly once, and writes the rotated refresh token back.
  - the renewal's line: one renewal prints exactly one `renewed the sign-in` line with the new end, a second
    `current()` within the token's time prints none, and no value appears in it.
- **Documents:** `doc/commands.md`, the `vault renew` row; `doc/credentials.md`, *A sign-in that ends, renewed on the
  host*, one paragraph: how to see the renewal work without waiting for the end. A changelog file, *Added*.
- **No acceptance scenario:** the suite has no stand-in token service that a task's broker reaches. The live proof is
  porelo-codex [CX01](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx01)'s ChatGPT stage: the first one-word request before `vault renew`, the second in a task started after it.
- **Build estimate=60**, like porelo [B265](README.md#b265) (S, measured 16 minutes of Codex). Here a command and the
  broker's line with six tests, then the reactor and the VM's `vault` area. Built by Codex `gpt-6.1-sol` `xhigh`,
  since it writes the vault.

## Acceptance

- `porelo vault renew chatgpt` sets only `expires_at`, to the present. The next task's first request renews the sign-in
  on the host, and `vault list` shows the new end.
- An import, an API key and an unknown name are refused with their reasons and change nothing.
- No token, refresh token or client secret appears in the command's output.
- The broker prints one line when it renews, naming the new end and no value.
