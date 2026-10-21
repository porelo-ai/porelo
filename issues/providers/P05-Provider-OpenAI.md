# P05 — Provider OpenAI

**Status:** Implementing.
**Size:** S - a provider file, a test reading it, a row in `doc/credentials.md`, and a live scenario in an agent
repository; the account sign-in is measured apart and stays refused until it is.

**What must be true.** A task is served models from OpenAI, without the credential entering the
container.

## The shape

**Upstream.** `https://api.openai.com`, the API; https://platform.openai.com is where its keys are made.

**Reached how.** Its own agent, and any agent speaking its dialect - which is the most widely imitated one.

**Authentication.** An account sign-in or an API key.

**Can it be brokered?** The API key, by its shape: a bearer token on every request, as OpenRouter's and Copilot's
are, which the broker already swaps. The account sign-in is unverified.

The agents that can reach it are listed in
[the comparison](https://github.com/porelo-ai/porelo-project/blob/main/doc/agents-and-providers.md).

## Plan

A provider is data (`doc/credentials.md`, "Model providers"): nothing in the broker changes for an API key.

1. **Red first:** a test in `apps/app/.../SelectedProviderTest.java`, which reads the shipped `providers/`
   directory, that `openai` is shipped, serves `openai` under `/v1`, puts the key in `Authorization: Bearer`, and
   that an agent speaking `openai` or `native` can drive it.
2. **`providers/openai.yaml`:** `upstream: https://api.openai.com`, `dialects: { openai: "/v1" }`, `auth_header`
   and `auth_prefix` `Authorization` / `Bearer `, `token_env: { _default: OPENAI_API_KEY }`, and
   `unbrokerable: { oauth: ... }` naming the sign-in as not measured, so a sign-in is refused before a start rather
   than failing inside the task.
3. **`doc/credentials.md`:** a row in "The providers Porelo ships".
4. **Live:** a scenario in an agent repository that speaks `openai` or `native`, as its `openrouter` scenario does,
   with a fake key: OpenAI's own `401` proves the swap, `porelo task status` names `openai`, and the container holds
   no key. With a real key in the suite's secrets, a model answers.
5. **The sign-in, measured apart:** whether its token works as a bearer on `api.openai.com` or only on another host
   the agent's own client uses; when measured, its own `upstream` or the `oauth` entries, and `unbrokerable` is
   lifted. An account is provided for the measurement. What must exist at the service for it: an account with the
   plan its own agent signs in with, and an API key made at https://platform.openai.com for the live run with a real
   key. Nothing of the account - its name, address, key or token - goes into a committed file; what is committed is
   the measured shape.

## Acceptance

- A task reaches this provider through the broker, and the request arrives. Seen to fail: a request
  from a task to the broker's OpenAI route does not reach the upstream.
- The credential stays on the host; the container holds a task-scoped token. Seen to fail: a search
  of the container's environment, files and process arguments finds the real key or sign-in token.
- Which provider a task used is answerable afterwards from the task's own record. Seen to fail: the
  record of a task that used it does not name OpenAI.
- The account sign-in is measured with the account provided for it, and is either brokered or stays refused with
  what was measured written beside the refusal. Seen to fail: `unbrokerable` lifted with no measurement behind it, or
  anything of the account in a committed file.
- Its live scenario records one streamed answer of each format it serves and adds it as a fixture of the
  broker's usage reading ([B148](../base/README.md#b148)). Seen to fail: the fixture's line lacks a count the
  provider sent, or the agent's answer differs from the provider's beyond the added usage chunk.
