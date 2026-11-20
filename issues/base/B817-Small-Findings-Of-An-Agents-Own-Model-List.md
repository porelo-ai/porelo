# B817 — Small Findings of an Agent's Own Model List

```json
{ "$schema": "issue-1.json", "id": "B817", "title": "Small Findings of an Agent's Own Model List",
  "state": "Idea", "size": "S", "priority": "Low", "rank": 140, "blockedBy": [],
  "covers": "What the reading of an agent's own model list left: a broken installed agent answered as an unknown one, two contract sentences on when the vault is read, and three test gaps" }
```

**Why this size.** S: one daemon method's error, contract words and tests in `daemon`.

**What must be true.** A person asking for an agent's models is told the truth about why none came, and the paths
that decide where the list comes from are each held by a test.

## Found

Left by the reading of [B815](README.md#b815), each read in the code:

- `Models` with an agent that is installed but fails its handshake or `Describe` answers `InvalidParameter` for
  `agent`, as if the name were unknown (`InstalledAgents` keeps it among its failures and `find` answers empty). It
  should say the agent did not answer.
- `40-agent.varlink`: the agent's list needs no vault unlock, but where the provider declares a list and the vault is
  open, the vault is read and the provider asked first; the text should say so.
- Test gaps in `ModelsMethodTest`: the agent resolver is not asserted to receive the agent's name; the fallback
  for a stored credential the provider cannot broker is not tested; the production overload is tested only for an
  unknown agent, never for an installed one answering `from: "agent"`.
