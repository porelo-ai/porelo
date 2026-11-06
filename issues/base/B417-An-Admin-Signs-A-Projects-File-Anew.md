# B417 — An Admin Signs A Project's File Anew

**Status:** Review. For 0.6; a defect found in a walk.
**Severity:** high: removing, locking or demoting the admin who last signed a project's file stops every new start of that
project on every machine, and nothing in the product can repair it.
**Size:** M - a new verb and a field in `project show`, with a contract the interface reads, and it is a trust decision, so it gets a reading.

**What must be true.** When a project's file stands refused because its signer is no admin now, a current admin can read the file
and sign it, by a verb and from the interface. A person about to remove, lock or demote that signer knows this follows, and who
must do it. What they sign, they have read.

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

## The design

**What is signed.** `doc/decisions.md` says the admin who signs anew signs the whole file, and each of the file's refusals ends in
"an admin signs it anew": a signer no admin now, an unsigned change, an older file than this machine accepted. So the verb serves
them all, and what guards it is that the admin reads the file and why it stood refused first, as the signature makes it theirs.

1. **`porelo project sign PROJECT [--yes --base REVISION] [--id ID] [--json]`**, as `prune` shows and removes on `--yes`. Without
   `--yes` it changes nothing and answers `state`, `why`, `revision`, the newest signer and the file's text, which anyone with
   write wrote, so it is shown without control characters. With `--yes`, `--base` is required and refuses a repository that moved
   on (`moved-on`), so what is signed is what was read. A file that reads on this machine is signed too: another may refuse it as older than it accepted, and could not otherwise be got out of it.
2. **The commit**, rebuilt by the operator's word of 2026-11-06: an empty signed commit with the trailer
   `Porelo-Signs-Anew: project.yml`, which counts as the file's newest change, as for the organization, so the file stays byte for
   byte. A trailer anyone can write only says which signature to judge: a non-admin's is refused like their change, an unsigned one
   too. Asked again under the same id it answers that change.
3. **What it still refuses**: an acting key that is no admin's now (`not-allowed`), a file that does not read or names another
   project (`refused-state`): those are mended by hand, as a signature would not make them read.
4. **The interface offers it**, to an admin while the file stands refused, with the file to read first. To anyone else it says
   which admins may. `porelo-frontend`'s part, on this verb.
5. **Before removing, locking or demoting**: `project show` adds `file_signer`, the login the newest change's key belongs to, or
   null when it is nobody's now or it cannot be read. The interface warns from it, and names who signs next. No write of the
   organization reaches into the project's repository for this.

The words in `ProjectFile` name the verb.

## Plan

Read in the code on 2026-11-03 (`organization/git-tree`, `apps/app-organization`).

1. `ProjectFile.sign(change, address, keys)`: fetches, answers an id's own change, checks the acting key and `base` as `change` does,
   and does not stop at a refusal of the signer or the date. `ProjectFile.Read` gains the signer and the text for the look.
   Also `remember` after signing, so this machine's date moves on.
2. `ProjectSignCommand` beside `ProjectRepositoryCommand`, under `project`; `file_signer` in `ProjectShowCommand`.
3. Docs: `doc/commands.md`, `doc/decisions.md`'s entry on the newest change; a changelog entry.

**Red first:** `ProjectFileTest`: a file signed by an admin since removed reads refused; `sign` by a current admin makes it read; a
non-admin is refused and nothing is pushed; a `base` that moved on is refused; a locked admin's file reads again once unlocked. A
command test that `--yes` without `--base` is wrong use and that the look changes nothing.

**Estimate:** 180 minutes, of which 35 the chain on the VM and one reading by another model; the verb alone, not the interface.

## Checked

- `ProjectFileTest` signs through a real repository, each case against the rows a removal leaves. The acceptance suite holds no
  scenario of an organization, so no step of it was added; a walk is what tries this end to end.
- A slow clock on the signer's machine can date the commit before what another machine accepted, which then refuses it. Every
  change of the file has this already; signing adds nothing to it.
