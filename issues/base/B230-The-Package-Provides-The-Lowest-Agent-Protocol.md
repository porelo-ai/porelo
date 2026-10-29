# B230 — The Package Provides The Lowest Agent Protocol

**Status:** Analyzed; blocked by [B224](README.md#b224).
**Size:** M - small in code, two package descriptions and one check, but a contract every agent repository's package
reads, and it must be published before any of them depends on it.

**What must be true.** Porelo's `.deb` and `.rpm` each provide `porelo-agent-protocol-<LOWEST>`, the lowest agent
protocol this build accepts, so an agent's package can depend on it and the package manager refuses only a pairing
`porelo` itself would refuse; a test holds the provided number to `AgentProtocol.LOWEST`. Neither package declares
any agent protocol today. Asked for by the Claude Code adapter, which pi's and oh-my-pi's packages follow; it is published before any agent depends on it.

## Plan

Read in the code on 2026-10-20 (`dist`).

1. **One number, read from the code**: a property `porelo.agent.protocol.lowest` in the root `pom.xml` beside
   `package.glibc.floor` (`:68`), the value of [B224](README.md#b224)'s `AgentProtocol.LOWEST`.
2. **The `.deb`**: `dist/dist-deb/src/deb/control/control` gains
   `Provides: porelo-agent-protocol-[[porelo.agent.protocol.lowest]]` beside `Depends` (`:13`).
3. **The `.rpm`**: `dist/dist-rpm/pom.xml`'s rpm-maven-plugin gains
   `<provides><provide>porelo-agent-protocol-${porelo.agent.protocol.lowest}</provide></provides>` beside its
   `<requires>` (`:89`).
4. **The guard, where the second form could drift**: `dist/package-check` reads the built `.deb`'s `Provides` with
   `dpkg-deb -f` (`Main.java:267`) and the `.rpm`'s with `rpm -qp --provides`, and compares both with
   `AgentProtocol.LOWEST`, read from `porelo-agent-api` itself, not from the property.
5. `agents/README.md`'s *Protocol versions* says what an agent's package depends on: any of `porelo-agent-protocol-<n>`
   from its own lowest up to its `VERSION`, as the Claude Code adapter builds it.

**Red first:** package-check against packages built with the property one below `LOWEST` fails, naming both numbers;
today it checks no `Provides` at all.

## Acceptance

- `dpkg-deb -f porelo_*.deb Provides` and `rpm -qp --provides porelo-*.rpm` both name
  `porelo-agent-protocol-<LOWEST>`.
- On the ubuntu VM, an agent package depending on `porelo-agent-protocol-3 | porelo-agent-protocol-4` installs beside
  this package, and one depending only on a number below `LOWEST` is refused by `apt`.
