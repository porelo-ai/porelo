# B272 — A Sign-In Is Renewed On The Host On Request

**Status:** Idea.

**What must be true.** A person can renew one of an agent's own sign-ins now, on the host, with one command:
`porelo vault renew <name>`. It renews the way a task's broker does, inside one update of the vault under its lock, so
the rotated refresh token is written back and a running broker takes the new token. It prints only the new end, never a
token. It refuses a credential that is not Porelo's own sign-in (an import, an API key) and says why.

## Why

Measured on 2026-10-24 while preparing the live check of the ChatGPT sign-in for Codex: there is no way to see the
host's renewal work except to wait for the access token's end. The broker renews only within 60 seconds of it
(`TokenPurchase.MARGIN`), and a ChatGPT sign-in ends ten days after it was made.

Read in the code: `porelo vault put` is no way around it. It takes the secret on standard input and replaces the whole
entry, so a person would have to handle the access token by hand to change its `expires_at`. The settings cannot be
changed on their own.

So a sign-in whose renewal is broken is found only when it ends, in the middle of a task, ten days after the person
signed in.

## To be decided in the analysis

- Renew now, or mark the sign-in due, so that the next request through a running broker renews it. Only the second
  tests the broker's own path, the one tasks use.
- What it prints: the new end, and whether the refresh token was rotated, with no value.
- Whether `porelo vault list` should show the last renewal.
