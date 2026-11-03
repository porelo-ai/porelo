# B418 — Four Roles And A Pin That Follows The Organization Admins

**Status:** Implementing.
**Severity:** high: this changes who holds what in every organization, and what one stolen key reaches on every server.

**What must be true, by the operator's word of 2026-11-03.** Each level answers for the one below and may always create, change
and delete there.

| Role | What it holds |
|---|---|
| Owner | Owners and Organization Admins: `owners.yml` and a new file |
| Organization Admin | Cards, a project's card, machines, and the admins of projects and machines (`members.yml`'s admins, `host.yml`'s admins) |
| Machine Admin | The machine's card, its carried projects, its token, taking Porelo off |
| Project Admin | Developers, repositories and their address (`projects/<project>/repository.yml`), `project.yml` |

A Project Admin or Machine Admin does not name further admins of that level: that is the Organization Admin's. This changes today's
`org host admin add` and `project member role`.

The pin follows the Organization Admins. A Machine Admin's change or replacement by an Organization Admin takes effect on a running
server at its next follow, with no confirmation by the former admin, who may be gone. The operator knows that one stolen key of
this role reaches every server.

**What holds today, read in the code, nothing run.**

- Roles are `owners.yml`, each host's `host.yml` and each project's `members.yml`. No Organization Admin exists.
- An owner is what the rules in `TreeWalk` ask for wherever the table now says Organization Admin: removing or replacing a card
  (`before.owner` in the card rules), a project's card, adding and removing a host (`hostFile`), and a project's admins (`members`,
  limited by `adminsOnly`).
- A host's admin may change the whole of `host.yml`, its admins among them, as `hostFile` asks only `hostAdmin`. The table leaves
  them the carried projects.
- A project's admin may change the whole of `members.yml`, admins among them, and alone may change `repository.yml` (`address`). The
  table moves the admins up and leaves them the address.
- `TreePin` refuses a commit on a host's paths unless the pin holds its signer, and `follow` moves the pin only to that host's own
  admins, along what the pin vouches for. It is the code of the decision this reverses.

**What it takes.**

1. Roles, size L, on `main` before any pin changes. A new file for the Organization Admins, judged by `TreeWalk` and signed by an
   owner, not a field in `owners.yml`: `TreeFiles` refuses a field it does not know, so an older build would refuse every later
   change of the owners. Each rule above moves to its role, `host.yml` and `members.yml` each gain a rule on which part a signer may
   change, and `GitTreeWrites`, the `org` and `project` commands and their refusals follow. A machine on an older build does not read
   the new file, so it refuses every change an Organization Admin signs until it is updated, and goes on reading the rest.
2. The pin, size L. The pin holds the Organization Admins' keys, follows changes of the new file, and moves to a Machine Admin named
   by one of them. A commit to the new file touches no host's paths, so `TreePin` does not refuse it today; the first refusal would
   be an Organization Admin's commit on `hosts/<host>/`. On a new build the pin then accepts a signer the walk holds in the new file, so
   a server set up earlier needs no step of its admins, only the update, and its trust in that file rests on `TreeWalk`'s judging an
   owner's signature, which a host's pin does not bind today. `org fetch` answers the list for the host. What a server does with it, servers already set up, and how a server
   first takes the list are the host's half, filed in `porelo-host`.

**A page on all roles and rights.** Accepted only with a page of its own in `doc/`, linked from where organizations are explained,
naming each role, each right, and the files that belong to each in the git implementation.

**The decisions it rewrites.** In `doc/decisions.md`:

- "An owner does not move a host's pin" is reversed for this role. Its reasoning stays true and becomes the accepted cost.
- "An owner handing on a key changes the owners first": what its first commit moves changes.
- "A backup key replaces only the card of the person who filed it" and "A single admin keeps an offline backup key": a backup's reach
  is to be read again against a pin that also follows the role.
- "A project's lock does not bind an organization owner" and "A past commit is judged by the whole chain of its key": the owner they
  name becomes the Organization Admin.

The `org owner`, `org person`, `org project`, `org host` and `project member` rows of
`doc/commands.md` change too.

**What an owner's key reaches through this role.** An owner holds no machine directly, but one commit naming a key of their choice
in the new file makes that key an Organization Admin, and the pin follows it. So one stolen owner's key reaches every server's pin at
each server's next follow, as a stolen Organization Admin's key does. Today an owner's key reaches no server.

**A proposal to weigh, not decided.** The pin holds the role's keys, but root stays with the listed Machine Admins. Such a person then
gets root by a visible signed change naming themselves, on one server at a time and where it can be seen. It keeps a dismissed admin
replaceable, and it takes less on the host's side.

**Settled by the operator on 2026-11-03.** Taking Porelo off also removes the machine's entry, and a Machine Admin may do it. An owner
does not act as an Organization Admin without being listed, and an owner may list themselves.

**With the operator.** Who is the first Organization Admin in an organization that exists today, and at founding; whether root stays
with the listed Machine Admins. What does not hang on these is built first.

**Why the rules have a moment they start.** A reader walks an organization's whole history, a new machine from its first commit. A
rule that changes for every commit would refuse what owners and admins signed rightly before it, and everything resting on that. So
the file's first accepted commit is where the roles start: before it the rules that held then judge, after it these. It cannot be
removed, and `org create` files it empty. An organization that exists today runs as it did until an owner lists someone.

**Estimate:** roles 360 minutes, the pin 240, the page 60, about 90 of it the chain on the VM, one reading per step by another model.
