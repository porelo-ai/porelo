# B237 — A Name Granted Live Through A CNAME

**Status:** Analyzed.
**Size:** S - `ResolvedNames` in `shield` and its caller `ShieldWatchCommand`, maybe `ClearanceHub`; no contract.

**What must be true.** A name granted to a running task is reached as it is when the project names it at the start,
also when the name answers through a CNAME.

## Why

Measured in a task on the VM (`0.5.1~snapshot.307`, agent `stub`): `fuinorg.jfrog.io` answers through a CNAME
(`endpointdns-gcprod-2-use1-inc-lb.jfrog.io`). Granted to the running task with `porelo shield egress --task
--add-domain`, it resolved, but every connection was dropped (`porelo-drop` in `events.jsonl`, curl timed out after
40 s), even with the CNAME's target granted too. Named in the project file at the task's start, the same fetch
answered 200 in 0.6 s. A name without a CNAME (`github.com`) worked when granted live.

## Plan

Read in `DnsPolicy`, `RunningEgress`, `ResolvedNames`, `ShieldWatchCommand`, `ClearanceHub` and `GrantedNames`.

1. **Why a name named at the start works:** it gets an `nftset=` line, and dnsmasq adds every address it answers for
   that name, through a CNAME too. **A live grant** adds only `server=` lines and records the name
   (`RunningEgress`, `GrantedNames.add`); its firewall half is the clearance watcher, which maps a dropped address
   back to a name with `ResolvedNames.lookup` and allows it when `GrantedNames.covers` that name.
2. **The cause, read, not yet measured:** `ResolvedNames.nameFor` takes the name on the last `reply <name> is
   <address>` line, which under a CNAME is the target, not the name granted; the address is dropped and a person is
   asked. Granting the target too did not help, read in `ClearanceHub.handle`: an earlier verdict for the address,
   the unanswered prompt's, is checked before the grants.
3. **Fix:** `ResolvedNames` walks the CNAME chain back to the name the container asked for; `ShieldWatchCommand`
   builds `Blocked` with it, so the grant check, the name shown and `GrantedAddresses.add` all use the granted name,
   and narrowing it later finds its addresses.
4. **Red first:** `ResolvedNamesTest.namesTheNameAskedForWhenTheAnswerCameThroughACname`.

## Acceptance

- In a task on the VM, `fuinorg.jfrog.io` granted live answers 200 on the second try, with one drop at most in
  `events.jsonl`. Seen to fail first: today's 40 s timeout.
- `--remove-domain fuinorg.jfrog.io` afterwards removes its addresses; a name without a CNAME granted live still
  works.

## Open questions

- **Whether a live grant overrides an earlier verdict for the same address.** Over a prompt that timed out or a
  limit, it is safe; over a person's explicit deny, it is a choice of risk. Recommended: over a timeout or a limit
  only.

## To be checked

- The task's `dnsmasq.log` lines for a CNAME answer, read on the VM, and that the "target granted too" failure was
  the earlier verdict.
