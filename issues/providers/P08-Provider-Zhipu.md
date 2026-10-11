# P08 — Provider Zhipu

**Status:** Estimated.
**Size:** S - two provider files, a test reading them, rows in `doc/credentials.md`, and a live scenario in an agent
repository; nothing in the broker changes.

**What must be true.** A task is served models from Zhipu, without the credential entering the
container.

## The shape

**Upstream.** https://open.bigmodel.cn in China, https://z.ai internationally - one vendor behind two hosts.

**Reached how.** **An endpoint compatible with another vendor's dialect**, so an agent built for that vendor drives it.

**Authentication.** An API key, carried in a variable of its own that no agent definition names today.

**Can it be brokered?** Yes, by its shape: a key in a header on every request. The two hosts need no per-credential
upstream: the upstream is the provider file's (`ProviderDefinition.upstream`), not the agent's, so two hosts are two
provider files, each with its own vault entry.

The agents that can reach it are listed in
[the comparison](https://github.com/sokar-ai/sokar-project/blob/main/doc/agents-and-providers.md).

## Plan

1. **Red first:** a test in `apps/app/.../SelectedProviderTest.java` that `zhipu` and `zai` are shipped, each with
   its own upstream host, both serving `openai` and `anthropic-messages`.
2. **`providers/zhipu.yaml`:** `upstream: https://open.bigmodel.cn`, `dialects: { openai: "/api/paas/v4",
   anthropic-messages: "/api/anthropic" }`, `Authorization` / `Bearer `, `token_env: { _default: ZHIPU_API_KEY }`.
3. **`providers/zai.yaml`:** the same for `https://api.z.ai`, `token_env: { _default: ZAI_API_KEY }`.
4. **`doc/credentials.md`:** two rows, and a line that one vendor's two hosts are two providers with their own keys.
5. **Live:** a scenario in an agent repository given a URL endpoint, as its `openrouter` scenario does, against each
   host with a fake key; the vendor's own refusal proves the swap, and `sokar task status` names the provider. Not
   the stub: it is given a socket, and Sokar puts a dialect's path only on a URL endpoint
   (`CredentialWiring.endpointFor`), so the stub's fixed `/v1/messages` misses `/api/anthropic`.
6. **The two hosts' keys, measured:** whether a key made on one host is taken on the other, which would let one key
   serve both providers. The two provider files stay either way, since the upstream is the provider file's; what the
   measurement settles is the line in `doc/credentials.md` - one key stored under both names, or a key of its own
   for each. An account is provided for the measurement. What must exist at the service for it: an account on
   https://open.bigmodel.cn and one on https://z.ai, each with an API key. Nothing of the accounts - names,
   addresses, keys - goes into a committed file; what is committed is the measured answer.

## Acceptance

- A task reaches this provider through the broker, and the request arrives. Seen to fail: a request
  from a task to the broker's Zhipu route does not reach the upstream, for either host.
- The credential stays on the host; the container holds a task-scoped token. Seen to fail: a search
  of the container's environment, files and process arguments finds the real key.
- Which provider a task used is answerable afterwards from the task's own record. Seen to fail: the
  record of a task that used it does not name Zhipu.
- Whether a key made on one host is taken on the other is measured with both hosts' keys, and `doc/credentials.md`
  says what was found. Seen to fail: the documentation claims one key serves both, or that it does not, with no
  measurement behind it, or anything of the accounts in a committed file.

## To be checked

- Whether the anthropic-compatible path takes the key as `Authorization: Bearer` or as `x-api-key` - one header per
  credential type is all a provider file says today (Agent Core's, measured with a key).
