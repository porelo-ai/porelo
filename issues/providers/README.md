# Provider Requirements

One file per provider, plus those about providers as a class. Each carries its own
acceptance criteria so it can be judged done or not done.

The providers Porelo ships, each reached through the broker with the
real credential never entering the container, are in [Credentials](../../doc/credentials.md#the-providers-porelo-ships).

A provider is **data**: an upstream, a header, a prefix and a path, found by scanning a directory.
That is why there is no code to write for most of these, and why adding one is a file rather than
a release.

Grouped by state, as in [the base set](../base/README.md); within a group ordered by what to do next. [P09](README.md#p09) concerns
providers as a class, the others one provider each.

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="p10"></a>[P10](P10-A-Native-Agent-Reaching-A-Provider-It-Does-Not-Know.md) | M | `porelo` [P09](README.md#p09) | A native agent is told the resolved dialect in `SetupContext`, so it can register a provider it does not know with that wire format; a definition key saying so lifts [P09](#p09)'s refusal for such a provider. | 0 |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="p09"></a>[P09](P09-A-Native-Agent-And-A-Provider-It-Does-Not-Know.md) | M | - | A native agent is paired with every provider today and fails inside the task: `provider.knows` in the agent API and an `UNKNOWN_TO_AGENT` refusal. |
| <a id="p07"></a>[P07](P07-Provider-xAI.md) | S | - | `providers/xai.yaml` (`api.x.ai`, `openai` under `/v1`, Bearer), the subscription kept unbrokerable; a test, a doc row, a live scenario. |
| <a id="p08"></a>[P08](P08-Provider-Zhipu.md) | S | - | Two provider files, `zhipu` and `zai`: the upstream belongs to the provider file, so no upstream per credential is needed. |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="p12"></a>P12 | The Google Sign-In As A Provider | `providers/google-sign-in.yaml` serves `https://daily-cloudcode-pa.googleapis.com` with `cloud-code` and the Google sign-in as `Authorization: Bearer`; an agent may speak a second dialect under its own variable; the sign-in is renewed on the host with the client's own secret, kept hidden, and the login picks that client among those the agent's CLI holds by asking the service once per pair; an API key is refused before the start, naming `google`. Walked live: a task's Antigravity CLI answered, the container held only a phantom token, the sign-in was renewed with the picked client, the three hosts outside the model route refused. |
| <a id="p11"></a>P11 | Provider ChatGPT Sign-In | `providers/chatgpt.yaml` serves `https://chatgpt.com` with `openai` under `/backend-api/codex` and the ChatGPT sign-in as `Authorization: Bearer`, its account as `ChatGPT-Account-Id` filled by the provider key `credential_headers`; renewed on the host; an API key is refused before the start, naming `openai`. Walked live: a task's Codex CLI answered, the container held only a phantom token, the sign-in was renewed, `ab.chatgpt.com` refused. |
| <a id="p05"></a>P05 | Provider OpenAI | `providers/openai.yaml` serves `https://api.openai.com` with `openai` under `/v1` and the API key in `Authorization: Bearer`, refusing a ChatGPT sign-in before the start and naming `chatgpt`; proved through the broker in `porelo-codex`. |
| <a id="p06"></a>P06 | Provider Google | `providers/google.yaml` serves `https://generativelanguage.googleapis.com` with `google-generative-ai` under `/v1beta` and the API key in `x-goog-api-key`, refusing a Google sign-in before the start and naming `google-sign-in`; proved through the broker in `porelo-agy`. |

## Notes

Which agents can reach which of these, and which of them can be brokered at all, is surveyed in
[the comparison of agents and providers](https://github.com/porelo-ai/porelo-project/blob/main/doc/agents-and-providers.md) in `porelo-project`.
