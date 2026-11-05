# B439 — A Secret Echoed Back Reaches A Line

**Status:** Idea.

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
