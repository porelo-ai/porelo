# B587 — One Reader Of The Host's Forge Settings

```json
{
  "$schema": "issue-1.json",
  "id": "B587",
  "title": "One Reader Of The Host's Forge Settings",
  "state": "Implementing",
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
