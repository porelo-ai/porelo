# B216 — A Second Setup Says Nothing Changed

**Status:** Analyzed.
**Size:** M - one shell script, but a `--json` answer porelo-host's role reads, docs in `doc/running.md` and `doc/hosts.md`, and tests that stub the machine.

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

## Plan

Read in `dist/dist-setup/porelo-setup.sh` and `SetupScriptChannelTest`.

1. Today `--json` without `--list` prints nothing on standard output. The "already" lines cover the key, the source
   line, the account, linger, the subordinate ranges, the group and the drop; the base packages, porelo, the filter
   and `--with` are installed on every run, and the run ends without a summary. Under `--show` the read-only checks
   still run, so it can tell what would change.
2. **Every branch that writes names its part** (`key`, `source`, `packages`, `porelo`, `filter`, `with`, `account`,
   `linger`, `subids`, `between-users`, `drop`). **A package is installed only when it is missing or older than the
   repository offers**, read with `dpkg-query`/`rpm -q` against `apt-cache policy`/`dnf repoquery`.
3. **The last line** says `nothing changed` or `changed: <parts>` (`would change` under `--show`); **`--json`** without
   `--list` prints `{"changed", "parts"}` on standard output just before the exit. `apt-get update` and
   `dnf makecache` count as no change.
4. **Docs:** the usage text, `doc/running.md` and a row in `doc/hosts.md`.
5. **Red first:** `SetupScriptChannelTest.aShownRunOnAPreparedMachineEndsSayingNothingWouldChange`, with stand-ins for
   `id`, `loginctl`, `dpkg-query` and `apt-cache` and the script's fixed paths overridden for the test.

## Acceptance

- On a prepared machine, stubbed, the last line says nothing would change and no install is shown. Seen to fail
  first: `apt-get install` is shown today.
- `--json` without `--list` gives one object with `changed` and `parts`; on the VM a second real run reports
  `"changed": false` and the package versions are the same before and after.

## Open questions

- **Whether a second run upgrades an installed package** - porelo itself too - when the repository offers a newer
  one, and says `changed: packages`. The script says today it does not update a machine; yes means every `up` can
  roll a host to a newer porelo. Recommended: no, install only what is missing, and say an older one is there.

## To be checked

- Whether `apt-get install -y X` upgrades an installed X today while `dnf install -y X` does not, measured on both
  VMs; how the tests override the script's fixed paths without an environment variable root would read.
