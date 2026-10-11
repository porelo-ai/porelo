# P10 — A Native Agent Reaching A Provider It Does Not Know

**Status:** Idea.

**What must be true.** An agent that speaks `native` is told the resolved dialect (`openai`, `anthropic-messages`) in
its `SetupContext`, so it can register a provider whose name it does not recognise with that wire format and reach it
through the broker. An agent that can do this says `provider.knows: any` in its definition, which lifts the refusal
[P09](README.md#p09) gives before the start.
