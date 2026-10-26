# B233 — A Host Service Reached By Name

**Status:** Review: on a rented host the task reached the forge by name on 443 and nothing else; the two things found there are built, below.
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

## Found on a rented host

- **A drop to another port of the host is not recorded.** The task's reader passes an event on only if
  `BlockedConnection.worthAsking()`, which excludes every `169.254.*` destination as link-local noise, and under
  pasta the host is `169.254.1.2`: connections to its ports 3000 and 22 timed out, dropped, with no line in
  `events.jsonl`, while one to the VPN address `10.73.0.1:443` was recorded. A drop to the gate's address on any
  port but the gate's is recorded and never asked about, since an Allow would put the host's address into the allow
  set and open its web ports. Red first: the reader passing on a drop to the gate's address as an event that is not
  asked about.
- **`task start` does not name the host services.** Its resolver line counts only `egress.domains`, and its report
  of reachable names knows no `host_services`; it names each declared host service and its port 443.
- **Built for both:** the reader passes on a unicast TCP or UDP drop to an IPv4 link-local address, and the
  clearance watcher never asks about the host's address, which `task start` records as `host-address` in the task's
  state, or any IPv4 link-local address: such a drop is denied and journaled by `host`, and a client's Allow there
  is refused. `task start` names each host service with port 443 in its resolver line and below the hosts it can
  reach. Each was seen failing first in a unit test.
- Not a defect: the host's public address answered a task at once because under pasta it is the namespace's own
  address (`/proc/net/fib_trie` lists it as `host LOCAL`), so the connection never left the task.

## Acceptance

- In a task, `curl https://<declared name>/` reaches a service on the host's 443, and `getent hosts <name>` answers the
  mapped address. Seen to fail first: NXDOMAIN today.
- Another port of the host is dropped and logged; an undeclared name still answers NXDOMAIN.
