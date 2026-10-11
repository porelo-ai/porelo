# B41 — What a Packaged Agent Installs

**Status:** Analyzed.
**Size:** M - one contract change (the `Agent` type says what a package ships), one reader shared by the daemon and the
CLI, and its tests.

**What must be true.** An operator asking `sokar agents --supply-chain` about a packaged agent is
told what that agent's package ships, read from the bill that package installed.

## Why

The change is Sokar's: `--supply-chain` is the CLI's output, the bill is what a package already
installs, and nothing in it belongs to one agent.

`sokar agents --supply-chain` answers well for an agent that fetches its tool and says almost
nothing for one whose package carries it. The gap is narrower than it looks and was measured
rather than assumed, on the fedora VM: a packaged agent **does** report its version -
`pi ... installs: 0.85.0` - because that comes from the definition. What is empty is the artifact
list, correctly, because nothing is fetched. So this is about *what the package carries*, not
about the version.

The answer already exists on the machine. Every agent package installs a CycloneDX bill at
`/usr/share/sokar/sbom/<package>.cdx.json`, and all three describe their payload
truthfully: `sokar-pi` covers its Node tree through the Maven-plus-npm graph, and
`sokar-claude-code` and `sokar-omp` record the CLI their image fetches, with the publisher's
SHA-256 and `sokar:delivery=fetched-at-image-build`.

## Plan

Read in today's code. `AgentsCommand` (`apps/app-agents/src/main/java/org/fuin/sokar/app/AgentsCommand.java`) prints
`installs:` from `definition.version()` and each `InstallArtifact`; the daemon's `AgentMethods`
(`daemon/src/main/java/org/fuin/sokar/daemon/AgentMethods.java`) maps the same artifacts into the `Agent` type of
`daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/40-agent.varlink`. A packaged agent's binary is
`AgentDirectory.PACKAGED/<package>` (`/usr/libexec/sokar/agents`) and its bill is
`/usr/share/sokar/sbom/<package>.cdx.json` - `agents/pom.xml` installs both under `${project.artifactId}`. So the
bill's path follows from the executable's file name: no agent name, and no change in the agent repositories.
`dist/package-check`'s `Bill` already parses these bills with Sokar's own JSON reader.

The question of who reads the file is taken as the `TaskInventory` precedent says: one class answers it, the CLI
uses it in its own process, and the daemon serializes the same answer for a remote client.

Steps, in order:

1. Red first: `PackagedBillTest` in `apps/app-model` with a bill of three components, one carrying
   `sokar:delivery=fetched-at-image-build`; then a missing bill and an unreadable one, each `UNVERIFIED` with a
   reason; then an agent outside the packaged directory, which has no bill to read and says so.
2. `PackagedBill` in `apps/app-model`, beside `TaskInventory`: for an `InstalledAgent`, the components (name,
   version, purl, shipped or fetched at image build) or the reason they cannot be read.
3. Contract: `Agent` gains `ships: []ShippedComponent` and `shipsUnverified: string` ("" when the bill was read),
   filled by `AgentMethods` from `PackagedBill` and described in `40-agent.varlink`. The interface is told on the
   channel.
4. `AgentsCommand --supply-chain` renders from the same reader: a count of shipped and fetched components, the list
   under `--verbose`, `UNVERIFIED - <reason>` for a missing or unreadable bill.
5. `AgentIsolationTest` (`apps/app`) already refuses an agent's name in Sokar's sources, which covers the last
   criterion.

The check against an installed `sokar-pi` is made on the fedora VM by hand; no acceptance leg installs a package.

## Acceptance

- For an agent whose package carries its payload, `--supply-chain` names what that package ships,
  read from the bill the package installed. Seen to fail: for `sokar-pi`, the output lists no
  component that its bill under `/usr/share/sokar/sbom/` holds.
- A component the image fetches rather than ships is shown as such - the bill already carries the
  property, so this is reading it rather than inventing it. Seen to fail: a component carrying
  `sokar:delivery=fetched-at-image-build` is shown as shipped.
- A missing or unreadable bill says so, and is distinguishable from an agent that ships nothing.
  The existing output already draws that line with `UNVERIFIED` and a reason; this keeps it. Seen
  to fail: with the bill removed or unreadable, the output is an empty list rather than
  `UNVERIFIED` with a reason.
- Nothing added here names an agent. The bill's path follows from the package, so it stays data.
  Seen to fail: an agent's name appears in the code added for this.

## Open questions

- **How much an operator wants printed** (the operator's). A Node tree is 162 entries. The plan prints counts and the
  list only under `--verbose`; since the daemon returns the components as data, an interface may choose differently
  without either being wrong.

**Guideline points, 2026-10-09:** a review of security guidelines counts this as its item F9,
answering NCSC/CISA secure development, OWASP `LLM03`, Sysdig 6, AISVS `C06`.
