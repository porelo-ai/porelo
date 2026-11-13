# B587 — One Reader Of The Host's Forge Settings

```json
{
  "$schema": "issue-1.json",
  "id": "B587",
  "title": "One Reader Of The Host's Forge Settings",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 9,
  "blockedBy": [],
  "covers": "porelo reads the host's forge.conf in one place; `porelo host push | pull` and `forge-token` share it."
}
```

**Why this size.** one reader in `app-model`, its two callers in `app-vault`.

Filed 2026-11-10 under ST291, from stacking [B544](README.md#b544) on [B584](README.md#b584).

**What must be true.** porelo reads the host's root-owned `/etc/porelo-host/forge.conf` in one place, with one set of
checks (a plain file only root can change, `name=` a forge name, `account=` once, the port), and every person's host
command takes the forge's name, its vault entries (`<name>.person`, `<name>.central`) and its port from that reader.

## Why

[B584](README.md#b584) and [B544](README.md#b544) were built at the same time and each read the file its own way:
`HostForgeSettings` in `app-model` (used by `porelo host forge-token` and the pull-request reader) and a private
`Settings` record in `HostGatherCommand` (`porelo host push | pull`). Both refuse what they do not know, but two
readers drift: a rule one learns the other does not.

## Plan

- `HostGatherCommand` takes `HostForgeSettings` (extended with what it needs, the port as a number) and its private
  record goes.
- Red first: a forge.conf `HostForgeSettings` refuses is refused by `porelo host push` with the same words.

## Built

- **One reader for each person's host command.** `porelo host push | pull` (`HostGatherPush`), `porelo host
  forge-token` and `HostPullRequestReader` all take the forge's name, its vault entries and its port from
  `HostForgeSettings`, and their refusals from its `refusal(read, file, port)`. `HostGatherPush`'s private `Settings`
  record and its own parsing are gone.
- **`HostForgeSettings`** (app-model):
  - the port is now an `int` (0 when none or not valid)
  - new: `refusal`, `validPort`, `centralEntry` and `NOT_ROOTS`
  - `entryName()` is now `personEntry()`
- **The checks the private reader made all hold, in the same words and order:**
  - a plain file only root can change, at most 64 KiB, strict UTF-8
  - `account=` exactly once and valid
  - a valid `name=`
  - a valid `git_port`, at most 65535
  - CR line endings stripped

  One change: a second `git_port=` line is refused, where push used to take the last one.
- **Tests.** `HostForgeSettingsTest` is new. `HostGatherCommandTest.pushRefusesWhatTheSharedReaderRefusesInTheSameWords`
  has five cases, and push exits 69 in each, with `forge-token`'s words. The case with `git_port` given twice failed
  before the change (exit 0). The targeted run on the build box was green:
  - `buildbox-porelo-0344425f9ee5-20261113T171440Z.log`
  - 116 tests across HostGatherCommandTest, HostGatherPushTest, HostForgeSettingsTest, HostForgeTokenCommandTest and
    HostPullRequestReaderTest
  - 0 skipped
- **Left out:** `HostPush` (app-gate), `HostForgeAddress` and `HostForgeLending` (app-model) and the `host/` module
  still read `git_port` themselves. None of them is a person's host command.
