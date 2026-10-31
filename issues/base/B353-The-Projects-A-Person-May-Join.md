# B353 — The Projects A Person May Join

**Status:** Backlog (operator, 2026-10-31), sprint 6m.
**Size:** M, proposed - one verb joining the registry's read and the access file's check, with no rule of its own.

**What must be true.** A person's Porelo answers which projects they may join, from the organisation's registry, without
the interface reading a file or checking a signature itself. `porelo registry joinable <checkout> --login L --json` reads
the registry as [B339](README.md#b339) does, and for every project a host's access file names, fetches and checks that
project's access repository as [B342](README.md#b342) does. It answers:

- `projects`: each project whose verified access file admits L, as `{"project", "host", "host_account", "host_card",
  "source", "access_repository", "revision", "signers", "admins"}`. `host_card` holds the host card's accepted values
  (`address`, `ssh_port`, `ssh_host_key`, `homeserver`, `services`), so the one tunnel is opened from the accepted card,
  never from a name alone. `revision` is the verified access commit, and `signers` are the project's trusted signers.
- `refused`: each project whose access repository cannot be read or does not verify, as `{"project", "host",
  "access_repository", "why", "admins"}`. It is never left out, and it never shows as an empty project list.
- `admins` are the verified admins who may grant access, in the registry's contact shape: `login`, `name`, `email`.

A forged, revoked or stale admission is refused, and nothing is followed or written: the verb only reads.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A project whose verified access file admits L is answered under `projects`, with its host card, revision and signers.
- A project whose access file does not name L, or names them `locked`, is not answered under `projects`.
- An access repository that cannot be fetched, or whose access file breaks its signature rule, is answered under
  `refused`, naming its address, its host and its admins.
- A registry that does not verify answers nothing under `projects`.
- Seen to fail first: there is no verb that answers the projects a person may join today.
