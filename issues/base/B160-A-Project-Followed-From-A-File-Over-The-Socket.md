# B160 — A Project Followed From A File Over The Socket

**Status:** Analyzed. Blocks `sokar-frontend` [F104](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md#f104).
**Size:** M - one contract change (a method, a parameter, two fields), the daemon, hand-in's part code shared, docs.

**What must be true.** A client follows a project from a file as `sokar project follow NAME FILE` does: from a path on
the machine, and from a bundle the person has on their own computer, which the client hands over in the call itself.

## Why

Since [B137](README.md#b137) an offline project is followed only from a file, and the interface follows only from an address. So an
offline project cannot be made from the interface at all. Two pieces are missing on Sokar's side, found on 2026-10-10:

- `Follow(name, url, ...)` already passes `url` to the same `Reconcile` as the command, so a path counts as a file.
  But the daemon does not make a relative path absolute, as the command does, and the contract does not say that `url`
  may be a path.
- A bundle the person has on the computer the client runs on cannot reach the machine outside a task: `HandIn` writes
  only into a running task's `/sokar/files`.

## The shape

- **A path:** `Follow` resolves a relative `url` that is a path against the account's home, as the command resolves
  it against its working directory, and records it absolute. The comment on `Follow` in `20-project.varlink` says
  that `url` may be an absolute path on the machine, a bundle or a directory, read once and never fetched in the
  background.
- **A bundle's bytes:** the client hands the bundle over in parts before `Follow`, as `HandIn` does, since one
  request is at most 4 MiB. The daemon writes it where only the account reads it, follows from it, and keeps it as the
  followed source for a later follow from a newer bundle. A refusal - an offline definition from an address, a
  signature that does not check, a bundle that is no bundle - is said by name as for a path.
- A size limit for the bundle, said in the contract and refused by name when exceeded; the interface shows a large
  upload with its progress.

## Plan

Read in today's code:

- `Follow` (`daemon/.../ProjectMethods.java`) passes `url` as it came (`SokarDaemon.address` refuses only a leading
  `-`) to `FollowSignedBy.follow`, the class the command uses. `ProjectFollowCommand` makes a path absolute against
  its working directory first; the daemon does not.
- Whether a source is a file is told by its form alone, `FollowedProjects.fromAFile(url)`: no scheme and no host
  before a colon. `Reconcile` and `ConfigurationWatch` decide by it that a file is read once and never fetched.
- `Followed` in `20-project.varlink` has `url` but nothing saying it is a file, so a client today has to parse it.
- `HandIn` (`15-task.varlink`, `HandIns` in `app-model`) already carries a file in parts: a request is at most 4 MiB,
  each part repeats name, bytes and sha256 with its offset, `PartOutOfOrder` says where to go on, an unfinished one
  is dropped after 10 minutes, and `FileTooLarge` refuses before a byte is kept. A bundle needs exactly this, and a
  whole bundle in one parameter of one call cannot pass the 4 MiB request limit.

The answers to the two questions, as planned:

- **Size limit and parts:** a bundle comes in parts, as a hand-in does, never in one call. The limit is 1 GiB
  (1 073 741 824 bytes), a constant said in the contract; the parts are written to disk as they come, never held in
  memory. A larger one is refused as `BundleTooLarge(bytes, limit)` before any byte is kept. The progress the
  interface shows is `received` against `bytes`, which every part answers.
- **How "followed from a file" is told:** `Followed` gains `fromFile: bool`, computed by
  `FollowedProjects.fromAFile(url)` on the machine, so a client never parses `url`; and `uploaded: bool`, true when
  the file is one a client handed over, whose `url` is then the absolute path where the daemon keeps it. A client
  shows "followed from a file", offers "follow again from a newer file" for it, and no refresh from an address.

Steps:

1. Red first, in `daemon` (`SokarDaemonTest`): `Follow` with a relative path follows the file under the account's
   home; a bundle sent in three parts through `PutBundle` and then `Follow` with `uploaded: true` follows an offline
   project; a bundle over the limit is refused as `BundleTooLarge`; a bundle signed by another key is refused as for
   a path; `Following` says `fromFile` and `uploaded`.
2. Extract the part assembly of `HandIns` (offset, sha256, abandonment, the lock per name) into one class in
   `app-model` that both use; `HandIns` keeps its own limit, record and placement.
3. `PutBundle(project, bytes, sha256, offset, part) -> (received, held: bool)` writes under
   `ProjectPaths.followed()`, in a directory only the account reads (`rwx------`). Held once the sha256 checks.
4. `Follow` gains `uploaded: ?bool`: with it, `url` must be empty and the held bundle is moved to
   `<project>/source.bundle` and followed from there; a later upload of the same project replaces it after its
   follow applied, never before. A relative `url` that `fromAFile` calls a path is resolved against the account's
   home and recorded absolute.
5. The comment on `Follow` says `url` may be an absolute path on the machine (a bundle or a directory) read once and
   never fetched in the background; `Followed` gets the two fields; `InterfaceDescriptionTest` holds the method, the
   parameter, the fields and the error.
6. `doc/commands.md` beside `project follow`, and the varlink page, say both ways.

## Acceptance

- Seen to fail first: a relative path sent to `Follow` follows the file the account means; a bundle's bytes handed
  over in parts follow an offline project; an oversized bundle is refused by name; the same signature checks as for
  a path.
- `InterfaceDescriptionTest` holds the new parameter and the refusal.

## To be checked

- Whether 1 GiB is the right limit for a bundle, or whether it comes from a setting (the operator's).
- Whether `fromFile` and `uploaded` are what the interface needs to show the source, or it wants one `source` word
  (Agent Frontend's).
