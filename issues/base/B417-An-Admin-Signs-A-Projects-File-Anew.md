# B417 — An Admin Signs A Project's File Anew

**Status:** Idea; a defect found in a walk. Its state and release are the operator's, who decides after the walk.
**Severity:** high: removing, locking or demoting the admin who last signed a project's file stops every new start of that
project on every machine, and nothing in the product can repair it.
**Size:** M - a new verb, the interface's offer on it and a warning on three writes, and it is a trust decision, so it gets a reading.

**What must be true.** When a project's file stands refused because its signer is no admin now, a current admin can read the file
and sign it, by a verb and from the interface. A person about to remove, lock or demote that signer knows this follows, and who
must do it. A file refused because somebody unknown changed it is not blessed in passing.

## What was seen

In a walk at GitHub, removing the project's founder, an admin, with another admin in place, left the project's page showing in red:
"the project.yml of `<commit>` is signed by `<fingerprint>`, which is no key of an admin of this project now: an admin signs it
anew". The removal stood.

## What holds today

Read in the code, not run:

- **The refusal is meant.** `ProjectFile.judged` takes the newest change to the file and asks `anAdminsKey`, over the rows
  `GitTreeOrganization.signingKeys` gives: members with an admin role, not locked. A removed admin has no row. Accepting the old
  signature by its date is no way out: the signer writes the date, so a removed admin could backdate a commit.
- **Nothing signs it anew.** `ProjectFile` has `read`, `add`, `remove` and `found`. `add` and `remove` go through `change`, which
  judges first and throws the same words. `found` needs an empty repository. No test signs anew. The words and `doc/decisions.md`
  name an act that only a hand-made signed commit can do.
- **Three ways in.** Removing the admin, locking them (`acting` leaves out a locked member) and changing their role.
- **Meanwhile.** Every new start answers "is not started" with 65 (`ProjectTaking`), and `project repository add` and `remove`
  are refused. `org fetch` does not read the file, so the organization's side of a host goes on.

The removal cannot sign in the same change: the organization and the project's file are two repositories and two commits, and the
person removing may be an owner with no admin key.

## The proposal

1. **`porelo project sign PROJECT`**: a current admin's signed commit over the file as it stands. It shows the file first, or takes
   `--revision` naming the commit it was read at in a script, so what is signed is what was read.
2. **What it signs**: it needs the file to change, since only the newest commit that changes the file is judged. Decide how,
   for example a line naming the signing, as `filedAnew` does for an address.
3. **The interface offers it** to an admin while this refusal stands, with the file to read first. Without an admin there, the page
   says which admins may. `porelo-frontend`'s part, on this verb.
4. **Removal, lock and role change say so** when the person is the file's newest signer: the answer names it, and the interface
   runs the signing next if the acting person is an admin, and otherwise names one.

## Plan

Read in the code on 2026-11-03 (`organization/git-tree`, `apps/app-organization`).

1. `ProjectFile.sign(change, address, keys, revision)`: like `change`, but it passes a refusal for a signer who is no admin now.
   Every other refusal stays one, such as an older file than this machine accepted or one that does not read.
2. `ProjectSignCommand`, beside `ProjectRepositoryCommand`, with `--id`, and `Tasks1` where the interface needs it.
3. The newest signer named in the answer of the three writes, read from the project's repository, with "cannot say" when it is out
   of reach, which then does not stop the write.
4. `doc/commands.md`, and `doc/decisions.md`'s entry on the newest change, which says an admin signs anew.
5. `ProjectFile`'s words name the verb.

**Red first:** `ProjectFileTest`: a file signed by an admin since removed reads refused; signed anew by a current admin it reads, and
a non-admin is refused. `sign` over a file an unsigned commit changed: decide what it answers, then hold it.

## To be checked

- Whether a person who signs must see an unsigned change's diff: the refusal "signed by nobody we know" and "signed by an admin
  no longer" are different cases, and only the second is what this issue is for.
- A locked admin unlocked again: the file reads again by itself, and a signing in between stays valid.
- A hand-made signed commit as today's way out, run on a local repository.
