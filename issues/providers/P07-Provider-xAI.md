# P07 — Provider xAI

**Status:** Analyzed.
**Size:** S - a provider file, a test reading it, a row in `doc/credentials.md`, and a live scenario in an agent
repository; the subscription account is measured apart and stays refused until it is.

**What must be true.** A task can be served models from xAI, without the credential entering the
container.

## Why

**Upstream.** `https://api.x.ai`, the public API; https://docs.x.ai describes it.

**Reached how.** Its own agent, and its public API from any agent.

**Authentication.** A subscription account, or an API key for the public API.

**Can it be brokered?** The API key, by its shape: a bearer token on every request, which the broker already swaps.
The subscription account is unverified.

The agents that can reach it are listed in
[the comparison](https://github.com/sokar-ai/sokar-project/blob/main/doc/agents-and-providers.md).

## Plan

1. **Red first:** a test in `apps/app/.../SelectedProviderTest.java` that `xai` is shipped, serves `openai` under
   `/v1`, and puts the key in `Authorization: Bearer`.
2. **`providers/xai.yaml`:** named `xai`, the name the provider-agnostic agents register it under ([P09](README.md#p09));
   `dialects: { openai: "/v1" }`; `Authorization` / `Bearer `; `token_env: { _default: XAI_API_KEY }`;
   `unbrokerable: { oauth: ... }` until the subscription account is measured.
3. **`doc/credentials.md`:** a row in "The providers Sokar ships".
4. **Live:** a scenario in an agent repository speaking `openai` or `native`, as its `openrouter` scenario does, with
   a fake key: xAI's own refusal proves the swap, `sokar task status` names `xai`, and the container holds no key.
5. **The subscription account, measured apart:** whether its token is a bearer on `api.x.ai` or reaches another host;
   then its own entries or a file of its own, and `unbrokerable` is lifted.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.
- **Seen to fail:** a test that sends a task's request through the broker goes red when it does not
  arrive at xAI; a search of the container for the real credential goes red when it is found; a
  test that reads the task's record afterwards goes red when it does not name the provider.

## Open questions

- Whether the subscription account its own agent uses can be brokered: unverified, and measurable only with an
  account (the operator's).
