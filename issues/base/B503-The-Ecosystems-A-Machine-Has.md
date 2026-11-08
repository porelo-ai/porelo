# [B503](README.md#b503) — The Ecosystems A Machine Has

**Status:** Backlog.
**Size:** M - a method in the agents' contract part (`40-agent`), read through `porelo-ecosystem-api`; a contract with
the ecosystems' repository, agreed first.

**What must be true.** A client of the daemon lists the ecosystem plugins installed on this machine and, for a
repository, what each would place for it at a first start, as the first start finds them.

## Plan

- `Ecosystems(repository?)` in `40-agent.varlink`, answered by the code the first start uses to find the plugins
  (`FirstStart`), which the method only renders.
- The answer's shape is agreed with the ecosystems' repository before it is built.
- Red first: a unit test with a stand-in plugin.

## Acceptance

- The installed plugins and what each places are answered as a first start finds them.
