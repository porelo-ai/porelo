# B182 — A Vault Entry A Declaration Names Is Used

**Status:** Review.
**Size:** S - one lookup in `vault put`'s warning, one line of output, unit tests and a step in an existing acceptance
scenario.

**What must be true.** `porelo vault put <entry>` does not warn that nothing will find the credential when a
declaration in `credentials.yml` reads it from that vault entry. It says instead which destination it is used for:
`used for ssh://forge.example.org`. The warning stays for a name that no declaration, no provider and no git name
asks for.

Today the warning compares the name only with the declared providers. After
`porelo credentials declare "ssh://forge.example.org" --kind ssh-key --vault forge-work`, then
`porelo vault put forge-work`, it says that no provider 'forge-work' is declared and that a task will only find the
credential if something asks for it by that name. But the declaration does ask for it by that name. Found in a test
against a self-hosted forge.

## Plan

Read in today's code: `VaultPutCommand.warnIfNothingWillUseIt` returns early for a name `GitCredentialNames.isOne`
knows. Otherwise it looks the name up in `context.providers()` and prints `unknownName(...)` when no provider has it.
The declarations are in `context.credentialRegistry().all()`, each with its `source` and, for `VAULT`, the entry as
`id`.

- **The lookup:** before the provider check, the records whose source is `VAULT` and whose `id` is the name. When
  there are any, `vault put` prints `used for <match>[, <match>...]` under its `stored` line on standard output and
  warns about nothing.
- **A kind that does not fit:** when a record for that entry declares a kind the stored value is not, `vault put` says
  so as a warning. An example is a token stored for an `ssh-key` record: the start would refuse it later as unusable.
  The kind of what was read is already known: `read ssh-ed25519` is printed from it.
- **A provider of the same name** still gets its own checks (`unbrokerableReason`): a name can be both.

Steps:

1. **Red first:** `VaultPutCommandTest`:
   - with a registry naming `forge-work` for `ssh://forge.example.org`, `vault put forge-work` prints
     `used for ssh://forge.example.org` and no "no provider";
   - without one, it warns as today;
   - with a `token` record for an ssh key, it warns about the kind.
2. The lookup in `warnIfNothingWillUseIt`.
3. **Acceptance:** in `credentials-commands.feature`, an entry of the person's own name, declared and stored, shows
   `used for` and not the warning; once the declaration is forgotten, storing it again warns as before.
4. Reactor, documents and the suite on the VM (`not @restart`).

## As built

- `VaultPutCommand.useOf` finds the declarations that read the entry and whether what was stored fits them. A private
  key is always recognised, so a value stated as nothing is no key for an `ssh-key` record. A token is not recognised,
  so for a token record no kind stated is no misfit.
- The `used for` line is said for every name, a `git.*` one included. The warning about no provider is left out when a
  declaration reads the entry. A provider of the same name still gets its own checks.

