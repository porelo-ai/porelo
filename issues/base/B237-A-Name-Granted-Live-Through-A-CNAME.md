# B237 — A Name Granted Live Through A CNAME

```json
{
  "$schema": "issue-1.json",
  "id": "B237",
  "title": "A Name Granted Live Through A CNAME",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 37,
  "blockedBy": [],
  "covers": "A name granted to a running task that answers through a CNAME is reached as it is when named at the start, where today its connections are dropped"
}
```

**Why this size.** `ResolvedNames` in `shield` and its caller `ShieldWatchCommand`, maybe `ClearanceHub`; no contract.

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

**Decided:**
- A live grant overrides an earlier verdict for the address only where no person gave it, a timeout or a limit; a person's deny stays.

## Acceptance

- In a task on the VM, `fuinorg.jfrog.io` granted live answers 200 on the second try, with one drop at most in
  `events.jsonl`. Seen to fail first: today's 40 s timeout.
- `--remove-domain fuinorg.jfrog.io` afterwards removes its addresses; a name without a CNAME granted live still
  works.

## To be checked

- The task's `dnsmasq.log` lines for a CNAME answer, read on the VM, and that the "target granted too" failure was
  the earlier verdict.

## Done

Built as planned.

- **The name asked for.** `ResolvedNames.nameFor` follows dnsmasq's lines for a CNAME: the name asked for with
  `<CNAME>`, each name of the chain, then the addresses under the last. Every address of that answer is the asked
  name's. A target asked for by its own name, after a new `query[` line, is that name. `ShieldWatchCommand` needed no
  change: it builds `Blocked` with what `ResolvedNames.lookup` gives, so the grant check, the name shown and the
  addresses recorded for the grant all use the granted name.
- **A grant over an earlier verdict.** `ClearanceHub.handle` lets a grant override a verdict for the address that no
  person gave: a question that ran out, one past the limit, or one still on screen, whose running out then no longer
  undoes the grant. A person's answer at the notification or from a client stays, also after a restore.
- **Red first.** `ResolvedNamesTest.namesTheNameAskedForWhenTheAnswerCameThroughACname` and three of the four new
  `ClearanceHubTest` cases failed; the fourth, that a person's deny stays, passed from the start, as it should.
  shield whole (88) and clearance whole (47) passed, and so did the root's `validate` and `-Pdocuments test`.
- **On the VM** (ubuntu, smith2, account-local `porelo` and `porelod` from this branch, the packaged hooks, the stub
  agent, a guarded project without a gate, clearance `prompt`):
  - The task's `dnsmasq.log` shows a CNAME as read: `reply fuinorg.jfrog.io is <CNAME>`, then
    `reply endpointdns-gcprod-2-use1-inc-lb.jfrog.io is 34.26.205.234`, and `... is NODATA-IPv6` for AAAA.
  - `fuinorg.jfrog.io` granted live answered 200 at the first try (1.8 s) after one drop, then 0.9 s and 0.75 s; the
    clearance log says `decided allow fuinorg.jfrog.io:443 (34.26.205.234) by granted`.
  - `--remove-domain fuinorg.jfrog.io` removed its address, and the next fetch failed at once. `github.com` granted
    live answered 200 twice.
  - Seen to fail first: the same steps with the packaged `porelo` of this morning timed out at 40 s twice, 4 drops.
- **Seen on the way, not changed here.** `shield egress --task` takes the container's name (`porelo-cnt_cnt-2`); the
  name `task list` shows (`cnt-2`) is refused as "no running task". A start refused at the gate left a reservation
  that `task remove --force` does not find, so `project clear` refused until the start was retried.
