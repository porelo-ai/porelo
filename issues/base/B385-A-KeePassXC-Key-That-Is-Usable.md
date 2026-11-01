# B385 — A KeePassXC Key That Is Usable

**Status:** Idea.
**Severity:** medium.
**Size:** M, proposed - `keepassxc-cli` run once for the entry and both attachments, a retry that finds the key it
made, an entry removed again when an attachment fails, typed refusals where the terminal or `/proc` is not what it
seems, and the verbs' exit codes aligned, each with its test.

**What must be true.** `identity create`, `identity rotate` and `identity revoke --lost` with `--keepassxc DATABASE`
are usable by a person at their terminal:

- The master password is asked once, not once for the entry and once for each attachment.
- A retry of `rotate` or `create` after `key-not-served` takes the key the first run put into the database, and never
  makes a second one.
- No orphaned or empty KeePassXC entry is left: when the entry was added but an attachment is refused, the entry is
  removed again, and the answer says what is left in the database, if anything.
- Under `su` or `sudo -u`, or with standard error redirected, the answer is a clear refusal that says why, never a
  `keepassxc-cli` that waits on a terminal nobody sees or reads the wrong one.
- A person who never types the master password gets a refusal that says so, not a process killed after ten minutes;
  with neither an agent nor a terminal the answer is `no-terminal`; a re-run with the same `--id` answers its `entry`;
  with `/proc` mounted `hidepid` the refusal says why.
- The three verbs answer the same refusal with the same exit code.

## Acceptance

- Tests with the stand-in `keepassxc-cli` count one password prompt for a whole create, see a retry after
  `key-not-served` make no second key, and see no entry left after a refused attachment; each seen to fail first.
- The stand-in's check that no file holds the key at any time also catches a temporary file that is deleted before
  it looks (`IdentityKeePassXcTest`, `KeePassXcStandIn`); today it would miss one.
- The redirection of `keepassxc-cli`'s standard input to the person's terminal is tested; today only a run against
  the real keepassxc-cli 2.7.10 shows it.
