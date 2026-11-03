# B407 — An Owner's Signature Counts For A Project's Files

**Status:** Idea, for after 0.7: the operator's word of 2026-11-03.
**Size:** M - a rule in the walk and in the judging of `project.yml`, a contract check, and a change on the host's side.

**What must be true.** An organization owner's signature counts for a project's `repository.yml`, `members.yml` and
`project.yml`, with no need to stand as the project's admin first. Host files are kept out.

**Why.** An owner reaches all three today in two steps, as `org project admin` names any admin, so the admin step adds a
visible commit and no protection. It has a cost: the signing keys come from today's members, so an owner who steps down
leaves a `project.yml` they signed refused again.

**Why host files stay out.** They count from the host's admin, and "An owner does not move a host's pin" holds because a
stolen owner's key must not carry the whole fleet. An owner's signature on a host's card could point every client at another
machine.

**What it needs, by reading on 2026-11-03, not run:**

- The walk knows the owners already. `project.yml` is judged from `signing_keys`, so each project's answer must also carry
  the owners' keys.
- `porelo-host` takes only the roles `admin` and `developer` in those rows and refuses a project's answer with another, so a
  new role needs a host change and a release of it. A row marked `admin` passes, but may make an owner a push signer on that
  host, which was not read further.

**To be settled:** a member's lock stays the project's own, as `adminsOnly` holds today, or a project's lock no longer binds an
owner in this file too. What the record shows of an owner stepping in, when no admin step names them.
