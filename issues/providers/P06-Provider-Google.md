# P06 — Provider Google

**Status:** Analyzed.
**Size:** S - a provider file with two dialects, a test reading it, a row in `doc/credentials.md`, and a live
scenario in an agent repository; the account sign-in is measured apart and stays refused until it is.

**What must be true.** A task is served models from Google, without the credential entering the container.

## Why

**Upstream.** `https://generativelanguage.googleapis.com`, the API; https://ai.google.dev is where it is described
and its keys are made.

**Reached how.** Its own agent, and provider-agnostic agents.

**Authentication.** An account sign-in or an API key.

**Can it be brokered?** The API key, by its shape: a header of its own, `x-goog-api-key`, which the broker already
finds and replaces (`VaultProxy.presented` names it). The account sign-in is unverified.

The agents that can reach it are listed in
[the comparison](https://github.com/sokar-ai/sokar-project/blob/main/doc/agents-and-providers.md).

## Plan

1. **Red first:** a test in `apps/app/.../SelectedProviderTest.java` that `google` is shipped, its first dialect is
   its own wire format under `/v1beta`, it also serves `openai` under `/v1beta/openai`, and the key goes in
   `x-goog-api-key` without a prefix.
2. **`providers/google.yaml`:** named `google`, the name the provider-agnostic agents register it under, so a
   `native` agent finds it ([P09](README.md#p09)); `dialects: { google-generative-ai: "/v1beta", openai: "/v1beta/openai" }`, the own
   format first so `native` gets it; `auth_header: { _default: x-goog-api-key }`, `auth_prefix: { _default: "" }`;
   `token_env: { _default: GEMINI_API_KEY }`; `unbrokerable: { oauth: ... }` until the sign-in is measured.
3. **`doc/credentials.md`:** a row in "The providers Sokar ships", saying both dialects.
4. **Live:** a scenario in an agent repository speaking `native` or `openai`, as its `openrouter` scenario does, with
   a fake key: Google's own refusal (`API_KEY_INVALID`) proves the swap, `sokar task status` names `google`, and the
   container holds no key.
5. **The sign-in, measured apart:** it reaches other hosts than the API key does; when measured, it is a provider
   file of its own or the `oauth` entries here, and `unbrokerable` is lifted.

## Acceptance

- A task reaches this provider through the broker, and the request arrives. Seen to fail: a task's request to it
  without the broker's token is refused by the broker.
- The credential stays on the host; the container holds a task-scoped token. Seen to fail: a scan of the
  container's environment and files for the real credential finds it.
- Which provider a task used is answerable afterwards from the task's own record. Seen to fail: the task's record
  after a request to it does not name Google.

## Open questions

- Whether the account sign-in can be brokered: unverified, and measurable only with an account (the operator's).

## To be checked

- Whether `x-goog-api-key` is taken on the `openai` path too, or that path needs `Authorization: Bearer` - one
  header per credential type is all a provider file can say today (Agent Core's, measured with a key).
