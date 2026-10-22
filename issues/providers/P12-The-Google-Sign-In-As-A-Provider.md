# P12 — The Google Sign-In As A Provider

**Status:** Idea.

**What must be true.** A task's Antigravity CLI is served models through the person's Google sign-in, brokered on
the host like the other sign-ins, with the real tokens never in the container. It is a provider of its own beside
the API key of [P06](README.md#p06), as [P11](README.md#p11) is beside [P05](README.md#p05).

## Why

Measured by porelo-claude-code's agent on Antigravity CLI 1.4.5, without a sign-in: the API-key path can be pointed
at a local endpoint, the signed-in path cannot be measured without one. Read in the binary, not measured: the
signed-in path takes its endpoint from `CLOUD_CODE_URL`, and the sign-in is kept in the desktop keyring. Google's
terms call third-party access with Antigravity's sign-in a breach that may suspend the account; `doc/credentials.md`
says so.

## To be checked

- With the person's Google sign-in on a VM, once, attended: where the sign-in is kept without a desktop keyring,
  which hosts the signed-in path reaches, and whether `CLOUD_CODE_URL` moves them.
