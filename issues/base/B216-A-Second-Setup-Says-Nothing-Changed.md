# B216 — A Second Setup Says Nothing Changed

**Status:** Idea.

**What must be true.** `porelo-setup.sh` run on a machine it already prepared changes nothing, and says so in a
form a program reads: a playbook that runs it on every `up` can report "unchanged" truly, without reading the lines
meant for a person.

## Why

Measured by porelo-host's `porelo` role on a rented host on 2026-10-19. A second run says "already" for the key, the
source line, the account, linger and the subordinate ranges. But it still runs `apt-get install` each time, and it
ends with no summary. So the role must read the "already" lines of the account's part to report `changed=0`, and it
cannot tell whether a package changed.

## The shape proposed

- A last line: `nothing changed`, or `changed: <the parts>`.
- `--json` without `--list`: `{"changed": false|true, "parts": [...]}` on standard output, everything else on
  standard error, as `--list --json` does.
- The packages installed only when one is missing or older than the repository offers, so a second run really
  changes nothing.

## To be checked

- Whether an upgrade of an installed package counts as changed, and how `--show` reports it.
