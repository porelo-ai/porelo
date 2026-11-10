# B441 — A Credential's Length Reaches No Agent And No Line

```json
{
  "$schema": "issue-1.json",
  "id": "B441",
  "title": "A Credential's Length Reaches No Agent And No Line",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 98,
  "blockedBy": [],
  "covers": "A credential's length is shown to its owner and reaches no agent and no log."
}
```

**Why this size.** a check of who can read two fields, and closing what it finds.

**What must be true.** A credential's length is shown to its owner and reaches no agent and no log.

## Why

A length is not secret from the person who holds the credential, so these stay: `vault put`, `vault list`, `vault import`,
`vault login`, the star per character in a prompt, and the daemon's fields for the person's own interface. To anyone else it
narrows a guess, and a line in a log outlives the moment.

The daemon answers with it in two places: `length` for an import and `characters` in a list, both in `VaultMethods`.

## To be checked

- Who can read those two fields over the daemon's socket, inside a task as well as outside it.
- Whether any log line or agent-facing output carries a length.

Close what reaches an agent or a line, each with a test seen to fail first.

## As built

**Who can read the two fields.** Only the account the daemon runs as. No task reaches the daemon's socket:

- A task's container gets exactly the mounts in `TaskRunner` lines 445 to 472: the certificate authority, the broker's own
  `vault.sock` in the task's state (`CredentialWiring` line 319), the agent's mailbox, its screen, the signing program and its
  guide. `TaskRunner` is the only code calling `ContainerSpec.volume` or `volumeReadOnly`, and `ContainerSpec` adds no other
  `--volume`. `aTaskMountsNeitherTheDaemonsSocketNorADirectoryAboveIt` holds this, and went red with the socket planted as a mount.
- Its network namespace is private (`ContainerSpec` line 263), and the socket is a file, so a network reaches nothing of it. A
  login container runs on the host's network (`AgentLogin` line 571), mounts nothing, and is a person's own login.
- `porelo` is not in a task's image, so a task has no client either; the broker's socket answers provider requests, not the
  daemon's methods.
- Outside a task, the socket and its directory are owner-only (`VarlinkServer` lines 315, 345, 354), which `theSocketIsOwnerOnly`
  holds, and another user is refused at the connection (`VarlinkServer` lines 481 and 482). A forward over ssh arrives as that
  account.
- In a rootless container the root user is the account itself, so the peer check alone would let it in. The missing mount is what
  keeps a task out, which is why the test reads the mounts.

So `length` and `characters` in `VaultMethods` stay as they are, as do `vault put`, `vault list`, `vault import`, `vault login` and
the star per character, each of which only its owner reads. `VaultEntry.suspicious`'s "only N characters" is said only by `vault
put` and an import, the daemon's for the person's own interface among them.

**What reached a line, closed.** `Credential.toString`, in the agent API, and `VaultEntry.toString` printed the secret's length, and
both exist to be what an accident prints. Neither says it now.

**Read, nothing to close.**

- The broker's log lines (`VaultProxy`) carry the method, the redacted target and the status; `VaultServeCommand` and
  `VaultRelayCommand` print names, addresses and "token not shown".
- `TransportLifecycle.withheld` writes a fixed `[withheld]` whatever it replaces. It compares a length to decide how to match, and
  says nothing of it.
- A phantom token is the same length whatever it stands for (`PhantomToken.mint`), so what an agent holds says nothing of the real one.
- The varlink server logs no reply.

These last are read in the code rather than held by a test, as there is no length in them to fail on.
