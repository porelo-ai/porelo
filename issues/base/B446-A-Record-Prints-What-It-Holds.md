# B446 — A Record Prints What It Holds

```json
{
  "$schema": "issue-1.json",
  "id": "B446",
  "title": "A Record Prints What It Holds",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 100,
  "blockedBy": [],
  "covers": "A record holding a secret prints what it is for, not what it holds."
}
```

**Why this size.** sixteen records, one override each.

**What must be true.** A value holding a secret cannot be printed by accident: its `toString` names what it is for, and
not what it holds.

## Found

[B439](README.md#b439) listed these as able to print a secret, with nothing doing so today, and left them for later.
Codex's reading of [B441](README.md#b441) named them again. They hold a token, a client secret, a device code, a file's
content, a task's environment, or a program's output.

## What Was Done

`toString` is overridden on `SetupContext`, `ContainerFile`, `TokenExchange.Granted`, `TokenPurchase.Client` and
`Refresh`, `DeviceGrant.Started`, `Outcome` and `Client`, `CodeGrant.Client`, `Grants.Service`, `TaskSecrets.Tokens`,
`TaskHelpers.Helper`, `CredentialWiring.CredentialPlumbing`, `TaskLaunch.Running`, `TransportSend.Acting` and
`CommandResult`. Each test was seen to fail first.

`CommandResult` now prints only its exit code, as a program's output may hold anything. Whoever wants the output in a
message names the stream.

A last reading added two: a URL written with a credential prints without it in the five records that hold an address
(`Grants.printable`), and `CodeGrant.Started` prints its port only, as its link holds the grant's state.
