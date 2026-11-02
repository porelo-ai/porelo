# B382 — A Key Added While KeePassXC Runs Is Served

**Status:** Review.
**Severity:** high.
**Size:** S.

**What must be true.** A key that `identity create --keepassxc` adds to a database the running KeePassXC has open
is served by KeePassXC's agent without the person locking and unlocking the database by hand and without a second
run with `--key`.

## Measured

KeePassXC 2.7.10 with its SSH agent integration on, the database open: an entry `keepassxc-cli` adds with the key
and `KeeAgent.settings` is not served, neither within 60 s nor after KeePassXC's D-Bus `openDatabase` of the open,
unlocked database. It is served once the database is locked (`keepassxc --lock` or D-Bus `lockAllDatabases`) and
opened again with its password, or when KeePassXC starts anew. `keepassxc <database>` hands the database to the
running KeePassXC and returns at once ("Another instance of KeePassXC is already running."); `keepassxc --lock`
answers "Databases have been locked." when KeePassXC runs and "KeePassXC is not running. No open database to lock"
when it does not, exit 0 both times, in English under a German locale too.

Unlocking through D-Bus `openDatabase(file, password, keyfile)` would serve the key with no step by the person, but
it needs the master password in Porelo, which only `keepassxc-cli` reads; so the person types it into KeePassXC's
own window.

## Acceptance

- A key the agent does not serve within 3 seconds makes Porelo lock the running KeePassXC's databases, ask it to show
  the database, say on the terminal to unlock it in KeePassXC's window, and wait; once it is unlocked the card is
  made, exit 0, with no `--key` run.
- A key served within the 3 seconds locks nothing.
- With KeePassXC not running, no window is started, and `key-not-served` comes at once, saying KeePassXC is not
  running and naming `--key`.
- Seen to fail first: `IdentityKeePassXcTest`'s two new tests and the changed `key-not-served` test.

## Done

- `KeePassXcKey` waits 3 s, then runs `keepassxc --lock` with `LC_ALL=C` and reads its words; when it locked, it
  hands the database to KeePassXC detached (`sh -c '"$1" "$2" … &'`), so a KeePassXC that quit since the lock opens
  its own window without holding the command, and writes one line to the terminal `keepassxc-cli` talks on. The
  wait that follows is the 60 s it was. `key-not-served` names what happened: the databases locked, or KeePassXC not
  running.
- Tests (`IdentityKeePassXcTest`): a stand-in `keepassxc` that is running or not; a stand-in `keepassxc-cli` that
  keeps the key in memory until the stand-in window stands for the person unlocking the database, then serves it.
- The stand-in that holds the key until the unlock gives up after 30 s or once its test's directory is gone, so no
  stand-in outlives its test.
- Not claimed: a run against the real KeePassXC window; the person's other databases stay locked until they unlock
  them.
