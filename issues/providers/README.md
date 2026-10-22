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

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="p05"></a>[P05](P05-Provider-OpenAI.md) | S | - | `providers/openai.yaml` (`api.openai.com`, `openai` under `/v1`, Bearer), a ChatGPT sign-in refused before a start; file, test and doc row built; the live run on an API key green through the broker in porelo-codex [CX01](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx01). |
| <a id="p11"></a>[P11](P11-Provider-ChatGPT-Sign-In.md) | M | - | `providers/chatgpt.yaml` (`chatgpt.com`, `openai` under `/backend-api/codex`, the sign-in as Bearer) and a provider key `credential_headers` that fills `ChatGPT-Account-Id` from the stored sign-in; the agent side is porelo-codex's; built and unit-tested, the VM next. |

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="p10"></a>[P10](P10-A-Native-Agent-Reaching-A-Provider-It-Does-Not-Know.md) | M | `porelo` [P09](README.md#p09) | A native agent is told the resolved dialect in `SetupContext`, so it can register a provider it does not know with that wire format; a definition key saying so lifts [P09](#p09)'s refusal for such a provider. | 0 |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="p09"></a>[P09](P09-A-Native-Agent-And-A-Provider-It-Does-Not-Know.md) | M | - | A native agent is paired with every provider today and fails inside the task: `provider.knows` in the agent API and an `UNKNOWN_TO_AGENT` refusal. |
| <a id="p06"></a>[P06](P06-Provider-Google.md) | S | - | `providers/google.yaml`: its own format under `/v1beta` and `openai` under `/v1beta/openai`, `x-goog-api-key`. |
| <a id="p07"></a>[P07](P07-Provider-xAI.md) | S | - | `providers/xai.yaml` (`api.x.ai`, `openai` under `/v1`, Bearer), the subscription kept unbrokerable; a test, a doc row, a live scenario. |
| <a id="p08"></a>[P08](P08-Provider-Zhipu.md) | S | - | Two provider files, `zhipu` and `zai`: the upstream belongs to the provider file, so no upstream per credential is needed. |

## Notes

Which agents can reach which of these, and which of them can be brokered at all, is surveyed in
[the comparison of agents and providers](https://github.com/porelo-ai/porelo-project/blob/main/doc/agents-and-providers.md) in `porelo-project`.
