# B768 — The Setup Script Asks the dnsmasq the Hooks Run

```json
{
  "$schema": "issue-1.json",
  "id": "B768",
  "title": "The Setup Script Asks the dnsmasq the Hooks Run",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 77,
  "blockedBy": [],
  "covers": "`porelo-setup.sh` checks `nftset` support of the `dnsmasq` in `/usr/sbin`, `/usr/bin`, `/sbin` or `/bin`, the one the hooks take since [B249](README.md#b249), never of one found on root's `PATH`; its test's stand-in moves to where the script looks",
  "openQuestions": 0
}
```

**Why this size.** XS: one check in one shell script and the stand-in of its one test, no contract another
repository reads. Estimate: 9 minutes (Core's median for XS).

**What must be true.** The setup script says `dnsmasq has nftset support` only about a `dnsmasq` the hooks would run.

Filed 2026-11-14 on the coordinator's order, from the agy reading of [B249](README.md#b249).

## Why

Since [B249](README.md#b249), the resolver hook, `porelo shield dns` and `porelo doctor` take `dnsmasq` only from the
system's root-owned directories (`SystemProgram.find`). `dist/dist-setup/porelo-setup.sh`, in "the one thing that
fails silently if it is missing", still runs `dnsmasq --version` by name. With a `dnsmasq` built with `nftset` in
`/usr/local/sbin` and none, or one without it, in `/usr/sbin`, the script says `dnsmasq has nftset support` and exits
0, while every task starts without a working resolver.

## Plan

- `porelo-setup.sh`: take the first of `/usr/sbin/dnsmasq`, `/usr/bin/dnsmasq`, `/sbin/dnsmasq`, `/bin/dnsmasq` that
  is executable, as `SystemProgram.find` searches; none found is said as the missing case it already says (exit 5,
  with the install hint). The `--show` line names the path it would run.
- `SetupScriptChannelTest`: its stand-in `dnsmasq` sits on `PATH` today. The script's fixed directories are replaced
  in the test as `/etc/subuid` and the others already are, so the stand-in is found where the script now looks.

## Built

- `porelo-setup.sh` takes the first executable `dnsmasq` in `DNSMASQ_DIRS` (`/usr/sbin /usr/bin /sbin /bin`, the
  order `SystemProgram.find` searches) and asks that one; none found is the missing case it already said (exit 5).
  It names the path it asked (`/usr/sbin/dnsmasq has nftset support`), and `--show` names the path it would run.
  Ownership stays the doctor's to say, as the plan's risk decided.
- `SetupScriptChannelTest`: the copy's `DNSMASQ_DIRS` points at a directory of the test's own, as the other fixed
  paths do, and the stand-in sits there. `aDnsmasqOnlyOnThePathIsNotTheOneTheHooksRun` (a stand-in on `PATH` only:
  exit 5) and `theDnsmasqTheHooksRunIsAskedAndNamed` were red on the script before, and are green. `SetupScriptChannelTest`
  19, `SetupCommandTest` 10, `SetupCheckoutsTest` 3 are green.

## Red-first test

In `SetupScriptChannelTest`, a `dnsmasq` stand-in that says `nftset` on `PATH` only, and none in the replaced fixed
directory: the script must refuse with exit 5. It passes today's script, which finds the one on `PATH`.

## Risks

- Whether the script checks ownership as `SystemProgram.find` does (root-owned, writable by root alone) or only the
  place: the place is what makes the answer about the hooks' program; ownership is the doctor's to say.

## Blockers

None.

## Acceptance

- Seen to fail first, then green: the test above.
- The setup script's page, if it describes the check, says where it looks.

## Open questions

None.
