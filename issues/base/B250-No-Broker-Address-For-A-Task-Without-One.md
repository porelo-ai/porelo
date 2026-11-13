# B250 — No Broker Address for a Task Without One

```json
{
  "$schema": "issue-1.json",
  "id": "B250",
  "title": "No Broker Address for a Task Without One",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 77,
  "blockedBy": [],
  "covers": "An agent's setup hears the broker's address only when the task has a broker, where an agent reached by URL hears the relay's address without one and takes itself for brokered"
}
```

**What must be true.** An agent's setup is told the broker's address only when the task has a broker. A task started
without a credential tells it none, so `SetupContext.brokered()` is false there, as its contract says.

## Why

`SetupContext`'s `endpoint` is "empty when the task is not brokered", and `brokered()` is `!endpoint.isBlank()`.
`TaskLaunch.placeAgentFiles` runs without a credential too, on purpose: an agent's onboarding and trust files have
nothing to do with one. `CredentialWiring.endpointFor` then answers a `socket` agent from the environment, which is
empty without a broker, but answers a `url` agent with the relay's address unconditionally. So an agent reached by
URL is told it is brokered, and may write its configuration against `127.0.0.1:9419`, where no relay listens.

Found by the skill reading of 2026-10-22; the line is older than that round.

## To be checked

- Whether a `url` agent's relay is started without a credential at all, and what each packaged `url` agent writes
  when `brokered()` is true without one.
- A test with no credential and a `url` route, red first, asserting an empty endpoint.
