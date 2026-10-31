# B352 — A Person's Access Written By Porelo

**Status:** Review. Backlog (operator, 2026-10-31), sprint 6m.
**Size:** L, proposed - grew from M (one verb writing the access file and its cards, signed through the agent,
idempotent by id) with the follow's own check of the remote before and after the write, a chosen git environment, the
signer rules of the follow, the branch read from the server and a card read without following links.

**What must be true.** The project admin's Porelo writes a change to a project's access repository and signs its commit
with the admin's key through their own agent; the interface never writes `access.yml` or `cards/` itself.
`porelo project access <init|add|remove|lock|unlock> --project P --person L [--host H] --base REV --id ID --json`
answers `{"revision"}`, the commit it made:

- `init --project P --person L --host H --id ID` writes the project's first access file in the persons' form: the admin as
  its first person, role `admin`, `hosts: [H]`, with their card. It takes no `--base` and is refused when an access file
  exists; then the admin is added to another host by `add`.
- `add --host H` puts the person into `persons:` with their card in `cards/L.card` ([B342](README.md#b342)), or adds H to
  their `hosts`; `remove --host H` takes H out of their `hosts`, and the person goes when no host is left; `remove`
  without `--host` takes them off every host at once. `lock` and `unlock` mark them `locked` for the project.
- `--base` (every action but `init`) is the revision the change was made against: when the head of the access repository is another one, the
  change is refused with exit 65 naming both, so the caller reads again and asks anew.
- `--id` makes it idempotent: the same id with the same change answers the same revision without a second commit; the
  same id with another change is refused.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH. The commit is signed
through the person's agent by Git itself (`gpg.format=ssh`, the key's public half as `user.signingkey`).

## Acceptance

- `init` makes the first access file with the admin on H; a second `init` is refused.
- `add` makes a commit signed by the admin's key that `project verify --json` answers with the person and their card.
- `remove --host H` of a person's last host removes them; `remove` without `--host` removes them from every host.
- `lock` answers the person `locked: true`; `unlock` `false`.
- A stale `--base` is refused naming both revisions; a repeated `--id` answers the same revision with no new commit.
- Seen to fail first: there is no verb that writes the access file today.

## Done

- `porelo project access <init|add|remove|lock|unlock>` clones the access repository into scratch, writes the change
  with Git's plumbing and commits it signed through the admin's agent by a project signer this machine pins: exact
  principal, not retired, not in `revoked.krl`, taken as `SignerHandover.pinned` takes them; the line's options are
  judged only by the follow's own check of the commit. The access repository is the one `project.yml` names in the
  commit in force, read as the follow reads it (`Reconcile.readAt`, a `snippet_file` from that commit's tree); an
  `--unverified` follow is refused.
- Nothing is read from the remote's head before it passes the follow's own walk (`SignerHandover.access`) from the
  access commit in force, and the new commit passes the same walk before it is pushed, both from the commit in force
  and as a first follow (`inForce == ""`) walks it. With no access commit in force only a signed tip is taken, as a
  first follow takes it, and `init` needs an empty remote. The push is `--force-with-lease` against the head read,
  on the branch the server names; a detached HEAD is refused, and an unborn HEAD beside other refs too. The probe
  clone that asks an unborn HEAD's branch runs only when `ls-remote` lists no ref at all.
- `add` takes the card from `--card`, read without following a link, bounded and strict UTF-8, or, without it, only
  the repository's card of a person already listed under the fingerprint `access.yml` pins. A card whose key or
  predecessor `revoked:` lists is refused, and so is anything at `cards`, the card or `access.yml` that is not a plain
  file; blobs are read byte-exact and bounded.
- `--id` answers the same revision only from a commit the walk accepts, signed, with exactly one change id and one
  digest, whose first parent is `--base`; the same id there with another digest is refused with 65.
- Every process runs in a chosen environment: no `GIT_*` of the caller, `GIT_CONFIG_GLOBAL=/dev/null`,
  `GIT_CONFIG_NOSYSTEM=1`, `GIT_PROTOCOL_FROM_USER=0`, no hooks, and the agent from the person's own environment;
  other variables, proxies included, are the person's and pass. Every git that reaches the remote runs with
  `http.followRedirects=false`, `protocol.allow=never` and `always` for `file`, `git`, `http`, `https` and `ssh`, the
  transports a follow reaches a repository by. Every text it prints passes the same filtering as its neighbours'.
- Not claimed: a `CommandException` reaching the verb, a retried `init` on a repository nothing vouches for yet, and
  a `--card` swapped for a FIFO between its checks and its read, which can still make it wait (or, swapped back to a
  hard link of the file, be read from the FIFO). Unsigned commits since the access commit in force that change only
  other paths are carried under the new signature: a pinned card cannot be replaced that way, only removed. A remote
  HEAD switched to another branch between the read and the push is not seen, and no follow lock is held while the
  verb runs.
- Tests: `ProjectAccessCommandTest` (32), against real git, a real ssh-agent and a bare access repository by
  `file://`, one HTTP fixture answering 302; while the verb runs, a process of it that inherits its environment gets
  the caller's `GIT_*` and `HOME`, as outside a test. Each test seen red by a mutation of what it guards. Full suite
  4436 green. Three rounds of narrow readings by agy Gemini.
