# B233 — A Host Service Reached By Name

**Status:** Accepted.
**Size:** L - a design to settle first, and porelo-host's [HO08](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho08) builds on it; the build touches `core`, `shield`, `app-base`, docs and an acceptance step.

**What must be true.** A task reaches a service on its own host, such as a forge or a registry, by a name the project
declares, on 443, without the task reaching anything else on the host.

## Why

porelo-host's forge role ([HO08](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho08)) has tasks
reach the host's forge and registry by name on 443. Today a task resolves only declared names, through its own
resolver, and the address it gets is what the host's DNS gives; Porelo has no entry for "this name is the host
itself". The one host service a task reaches is the git gate, bound to loopback and mapped in at one address and one
port (`LoopbackMapping`).

## Plan

Read in `LoopbackMapping`, `GateWiring`, `NftRuleset`, `DnsPolicy`, `TaskRunner` and `Egress`.

1. **The gate's pattern:** pasta maps `169.254.1.2` to the host's loopback (`LoopbackMapping`), the gate binds
   `127.0.0.1` there, and the firewall opens exactly that address and port (`NftRuleset.gate`) in every class. A
   declared name resolves through the host's DNS and opens 80 and 443; nothing answers a name with the host.
2. **A project key naming host-local names**, checked as a domain is. `DnsPolicy` answers each with the host's
   address (`host-record=`), never asking upstream and with no `nftset=` for it; `NftRuleset` opens that address
   on 443 only. The address is the gate's (`wiring.gateAddress()`), so slirp4netns and podman 4 fall back as the gate
   does.
3. **Docs:** the egress section of `doc/project-file.md`, `doc/reach.md`, and the exposure in `doc/decisions.md`.
4. **Red first:** `DnsPolicyTest.answersAHostServiceWithTheHostsAddressAndAsksNoUpstream`, then
   `NftRulesetTest.opensAHostServiceOn443AndNoOtherPortOfTheHost`.

**Decided:**
- Host-local names are said in the signed `project.yml`, one key for every machine following the project.
- Port 443 only.
- It holds in `offline` too, as the gate does.
- The exposure - whatever listens on the host's loopback 443 is reachable by every task that names it - is accepted and named in `doc/decisions.md`.

## Acceptance

- In a task, `curl https://<declared name>/` reaches a service on the host's 443, and `getent hosts <name>` answers the
  mapped address. Seen to fail first: NXDOMAIN today.
- Another port of the host is dropped and logged; an undeclared name still answers NXDOMAIN.

## To be checked

- First, on the VM under pasta and on podman 4: whether a task reaches a host service at `169.254.1.2:443` bound to
  `127.0.0.1` or to every address, whether the host's LAN address works instead, and that TLS with the declared
  name passes unchanged; and that a root-run forge listens on loopback.
