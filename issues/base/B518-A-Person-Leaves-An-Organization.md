# [B518](README.md#b518) — A Person Leaves An Organization

**Status:** Backlog, since 2026-11-09, in ST295. Filed 2026-11-08.
**Size:** S - `porelo org leave`, on code that exists for revoking a card, plus forgetting the organization here.

**What must be true.** `porelo org leave` takes a person out of an organization in one step.
- It withdraws their own card, as `porelo org person revoke <me>` does.
- It gives up their own roles: owner, admin, host admin and project admin.
- It forgets the organization on this machine, which `porelo setup` cannot do today: it has only `--organization` and
  `--replace`.
- It refuses to leave the organization without an owner, and says who could take the place.
- It answers `--json`, so the window's *Leave <organization>…* can run it.

## Why

Agent Frontend's window shows *Leave <organization>…* as a waiting action (F429). Nothing does it today: `org owner
remove`, `org admin remove` and `org person revoke` act on one part each, and nothing forgets the organization here.
