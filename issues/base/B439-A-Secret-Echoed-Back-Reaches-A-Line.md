# B439 — A Secret Echoed Back Reaches A Line

```json
{
  "$schema": "issue-1.json",
  "id": "B439",
  "title": "A Secret Echoed Back Reaches A Line",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 101,
  "blockedBy": [],
  "covers": "Text Porelo did not write reaches no line unless known to hold no secret, and a value holding one cannot be printed by accident."
}
```

**Why this size.** the four paths that print today; the records stay for later.

**What must be true.** Text Porelo did not write, an error from a service or a helper's standard error, reaches no line
unless it is known to hold no secret, and a value holding a secret cannot be printed by accident.

## Found

From a search of the whole repository while taking a token's prefix out of every line. Read in the code and checked, not
run. **Prints it today, given a service or a helper that echoes:**

1. `Grants.error` returns the service's `error` field as it stands, though its comment says the code alone; revocation and
   client registration put it in an exception, and `DeviceGrant` does the same at both its failures. `TokenPurchase` already
   holds a list of the codes it will repeat.
2. `CommandPassphrase` puts a failing passphrase command's standard error in the exception.
3. `TransportLifecycle.redact` leaves a secret shorter than eight characters, and a multi-line secret, in the standard error
   it repeats.
4. `porelo vault credential` prints `password=` to a terminal as it does to git's pipe, when a person runs it by hand.

**Could print it, with nothing doing so today:** records holding a secret keep the default `toString`: `TaskSecrets.Tokens`,
`SetupContext`, `ContainerFile`, `TokenExchange`'s answer, `TokenPurchase.Client` and `Refresh`, `DeviceGrant`'s `Started`,
`Outcome` and `Client`, `CodeGrant`'s and `Grants`' clients, and the wrappers `TaskLaunch.Running`,
`CredentialPlumbing`, `TaskHelpers.Helper`, `TransportSend.Acting` and `CommandResult`.

**Open, a decision and not a defect:** a credential's length is said by `vault put`, `vault list`, `vault import`,
`vault login` and the daemon's `length` and `characters`, and a passphrase prompt prints one star a character. Whether
length counts as part of a secret is the operator's question. `ReadHostVaultShare` answers a whole share over the
owner-only socket, on purpose.

## As built

The ones that print today are closed. Only a code OAuth defines is repeated, from a callback too; a failure of the request is
said by its kind, as a parser's words quote what the service sent; an address is shown without its query, fragment or user;
a failing passphrase command is said by its exit code, and a person runs it to see the rest. A transport's every value is
withheld in both its failure and its streamed lines, in one pass; under eight characters only where it stands alone, so a value
of one letter leaves words readable, which leaves one echoed inside a longer word. `porelo vault credential` answers only a
pipe, a socket, a file or `/dev/null`, read from where standard output really goes, exit 2 otherwise, including where `/proc`
cannot say. **What it costs:** a TLS failure says its kind and no longer the JDK's words, and gpg's reason for a failed
passphrase command is seen by running it. The records and the question of length stay as found.
