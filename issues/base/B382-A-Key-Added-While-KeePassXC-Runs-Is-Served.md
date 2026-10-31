# B382 — A Key Added While KeePassXC Runs Is Served

**Status:** Idea.
**Severity:** high.
**Size:** S, proposed - measured against a running KeePassXC first; then either a way to have it serve the new
entry or words that say to lock and unlock the database.

**What must be true.** A key that `identity create --keepassxc` adds to a database the running KeePassXC has open
is served by KeePassXC's agent without the person locking and unlocking the database. It is not measured: if
KeePassXC serves an entry only once the database is opened, the answer is 65 `key-not-served` until the person
locks and unlocks it, and a retry with `--key` then takes the key.

Found while building [B356](README.md#b356) on 2026-10-31; not built tonight (operator's rule of 22:30Z).
