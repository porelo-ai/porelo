# B233 — A Host Service Reached By Name

**Status:** Review: published since `0.5.1~snapshot.329`; green on the VM in its acceptance suite, but for its two host-service scenarios, which need a listener on port 443 that only root can open.
**Size:** M - the design is settled; `core`, `shield` and `app-base` carry it, with docs and an acceptance scenario; porelo-host's [HO08](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho08) builds on it.

**What must be true.** A task reaches a service on its own host, such as a forge or a registry, by a name the project
declares, on TCP 443, without opening another host port beyond the gate.

## Why

porelo-host's forge role ([HO08](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho08)) has tasks
reach the host's forge and registry by name on 443. Today a task resolves only declared names, through its own
resolver, and the address it gets is what the host's DNS gives; Porelo has no entry for "this name is the host
itself". The one host service a task reaches is the git gate, bound to loopback and mapped in at one address and one
port (`LoopbackMapping`).

## Plan

1. **The gate's pattern:** pasta maps `169.254.1.2` to the host's loopback (`LoopbackMapping`), the gate binds
   `127.0.0.1` there, and the firewall opens exactly that address and port (`NftRuleset.gate`) in every class. A
   measured listener bound to exactly `127.0.0.1` is reached at `169.254.1.2` under pasta; a listener bound only
   to the LAN address is refused. Podman 4 was not measured. `GateWiring.gateAddress` probes the address, and
   `gateBind` falls back to binding every interface outside the working pasta mapping.
2. **`egress.host_services` is a list of names.** `Egress` validates them with the domain grammar;
   `ProjectReader` reads them and `ProjectSchema` declares them. `Project.egressFor` adds a repository's entries
   to the project's. A name in both `egress.domains` and `egress.host_services`, including across those blocks,
   is refused when read, naming the name and both keys. Offline permits host services but keeps its existing
   refusals of external egress. `EgressEdit` continues editing only sets and domains, preserving this key as text.
3. **`TaskRunner` carries the effective host services to both policies.** `DnsPolicy.hostService` answers each
   with `wiring.gateAddress()` through `host-record=`, never upstream and never through `nftset=`; a refused name
   stays refused. `NftRuleset.hostService` opens that address on TCP 443 beside the gate's rule, in every class.
   No host-service declaration adds no record or rule. The probed fallback address is used as the gate uses it;
   no separate address is guessed. `LoopbackMapping`, `GateWiring` and `TaskLaunch` need no new mapping or flag.
4. **Docs:** the egress section of `doc/project-file.md`, the control in `doc/reach.md`, the accepted exposure
   and its index row in `doc/decisions.md`, and an added changelog entry.
5. **Red first:** `DnsPolicyTest.answersAHostServiceWithTheHostsAddressAndAsksNoUpstream` and
   `NftRulesetTest.opensAHostServiceOn443AndNoOtherPortOfTheHost`; reader refusals, offline and repository merging;
   policy-file wiring and absence of undeclared grants. Exercise new methods with a deliberate defect that still compiles.
6. **Acceptance:** a written `@host-services` scenario uses the stub, skips through the kit's environment-variable
   prerequisite unless a root-run loopback TLS listener on 443 and its public test CA are prepared, and checks
   local DNS, HTTPS, another port's logged drop and an undeclared name. It is not run without that VM fixture.

**Decided:**
- Host-local names are said in the signed `project.yml`, one key for every machine following the project.
- Port 443 only.
- It holds in `offline` too, as the gate does.
- The exposure - whatever listens on the host's loopback 443 is reachable by every task that names it - is accepted and named in `doc/decisions.md`.

## Acceptance

- In a task, `curl https://<declared name>/` reaches a service on the host's 443, and `getent hosts <name>` answers the
  mapped address. Seen to fail first: NXDOMAIN today.
- Another port of the host is dropped and logged; an undeclared name still answers NXDOMAIN.
