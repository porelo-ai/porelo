# B385 — A KeePassXC Key That Is Usable

**Status:** Analyzed.
**Severity:** medium.
**Size:** L - a design to decide first (how one unlock serves three or more `keepassxc-cli` commands without Porelo
reading the master password), then `KeePassXcKey`, the three verbs, the stand-in and about a dozen tests; the
smallest piece (typed refusals for a terminal that is not the person's) is S on its own.

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

## Plan

Read on 2026-11-02 in `apps/app-vault` and measured against `keepassxc-cli` 2.7.10. What holds today:
`KeePassXcKey.make` (`KeePassXcKey.java:117-180`) generates the key (193), then runs `keepassxc-cli` three times through a
`sh -c 'exec 3<&0 <"$1" >>"$2" 2>&1; ...'` wrapper (258-276): `add`, then `attachment-import` for `id_ed25519` and for
`KeeAgent.settings`, the key and the settings on descriptor 3. Each process asks the master password on the person's
terminal, so three prompts, measured. Nothing removes an entry when an attachment fails (239-248); a second `make` makes a
second key (193) under a new default entry name (392-398), or is refused when the same `--entry` exists (measured: `add`
exits 1). `identity create` has no `--id`; a re-run of rotate/revoke with the same `--id` is answered by
`IdentityChange.kept`/`finish` without the entry (`IdentityRotateCommand.java:92-96`), and the operation holds no entry.
The terminal is judged by file descriptor 0 only (`StandardInput.java:45-52`), while the wrapper reopens
`/proc/<pid>/fd/0` and appends to `/proc/<pid>/fd/2`.

1. **Typed refusals for the terminal (the part that needs no design).** `KeePassXcKey.make` refuses before it runs
   anything, with the one exit code 64 and typed reasons, in all three verbs:
   - standard input and standard error must both be a terminal and the same one (`/proc/self/fd/0` and `/proc/self/fd/2`
     resolve to a `/dev/pts/*` or `/dev/tty*` that this user can open for reading and appending); otherwise `no-terminal`
     with the words saying which of the two is not (standard error redirected is the case the issue names);
   - the pts must belong to this process's user (`Files.getOwner`), otherwise a refusal `terminal-not-yours` ("under su or sudo
     -u the terminal is the other account's");
   - `/proc/self/fd` must be readable: if the link cannot be read (`hidepid` or no `/proc`), a refusal that says so; how
     `hidepid` shows is not established (To be checked).
   Tests: `IdentityKeePassXcTest` gets one test per refusal, run through the stand-in with the context's input a terminal and
   `terminalOut` a regular file, and with a terminal owned by another user (a fake `StandardInput`), each seen to fail
   first (today the stand-in is run and the answer is not a refusal).
2. **One master password per create (design to decide first).** Candidates, from the measurement: (a) `keepassxc-cli open DB`
   as one process fed `add`, `attachment-import` with the key on descriptor 3 and the settings on descriptor 4, and `exit`
   from a pipe, the password from the terminal; works with a pause between the password and the commands (measured), so
   the wrapper must first let `keepassxc-cli` read the password from the terminal and only then feed the commands; (b) the
   wrapper shell reads the password (`stty -echo; read -r`) once and hands it to each of the three runs on a pipe: the
   password then lives in a helper shell for the length of the command, which breaks "Porelo never sees the password"
   (`KeePassXcKey.java:30`); (c) keep three prompts and say so. The plan is (a), proved first by a spike against the real
   `keepassxc-cli` in a pseudo-terminal (`script`), and falls to the open question if no way that does not depend on timing
   is found. In every case the stand-in counts its prompts (one per invocation of `keepassxc-cli` that reads the terminal path).
3. **A retry takes the key already made.** Before a key is generated, `make` looks for the entry named by `--entry` (an
   entry is named by the first answer's `entry`, which `key-not-served` already carries): with `attachment-export DB ENTRY
   id_ed25519 --stdout` it reads the key back, parses it (`OpenSshPrivateKey`, vault module), and takes its fingerprint
   when the entry holds both attachments; the agent is then waited for as in a first run and no second key is made. An
   entry that exists without both attachments is not trusted: a refusal that names it and what is in it. Without `--entry`
   the default name has a timestamp, so a retry cannot find it; the first answer therefore says to give `--entry` for a
   retry (it names it already), and the three verbs do the same. The key read back is held in memory as it was at
   generation and cleared the same way (`Arrays.fill`).
4. **No orphan entry.** In `handOver` (187-250) an attachment that is refused removes the entry again, in the same unlocked
   session as step 2 if that is built (a second `rm` is needed for the recycle bin, whose name depends on the database's
   language, measured: `Papierkorb`/`Recycle Bin`; the group is found from `ls -R` or the entry is deleted from within
   `open`). The answer says what is left: "removed", "in the recycle bin as <group>/<entry> with the key attached" (the key
   is then in the database's recycle bin, which must be said), or "not removed: remove <entry> in KeePassXC". Test with a
   stand-in mode that refuses the second `attachment-import`, seen to fail first (today the entry stays and the words only
   say to remove it).
5. **A person who never types the password.** The `keepassxc-cli` runs through the wrapper get their own bound (the
   default ten-minute kill is `ProcessCommandRunner`'s), e.g. 120 s per prompt, and a timeout is answered as a typed refusal
   `no-password` "the master password was not typed in 120 s; nothing was kept" -- not as exit 127 (today
   `CommandException` is mapped to 127 = `key-store-missing`, `KeePassXcKey.java:273-275`, which would blame a missing
   program). With neither an agent nor a terminal the answer is `no-terminal` before the agent is asked (the order at
   124-144 already does this; pinned by a test). A re-run with the same `--id` answers its `entry`: the operation record gets
   the entry (an optional field of `IdentityOperation`, read back by `kept`), and `IdentityChange.print` is given it; for
   `create`, which has no `--id`, nothing is added (it makes no operation).
6. **The three verbs answer the same refusal with the same exit code.** One method maps `IdentityChange.Refused` to the answer
   (typed fields, `reason`, `words`, code); `IdentityCreateCommand.made` (257-283) and its own `Refused` class (101-122) use it
   instead of re-wrapping. A parameterized test runs each of the three verbs through each refusal of steps 1, 4 and 5 and
   asserts the same reason and the same code; seen to fail first on whichever differs.
7. **Acceptance in the stand-in.** `KeePassXcStandIn` counts prompts; its "no file holds the key" check watches while the key
   exists (a `WatchService` on the temp directory and the account's directories, started before the verb runs, reading each
   created file's head for the armour) instead of one look at `attachment-import` time; and a test makes the stand-in read
   from the terminal path given to it, so the redirection of standard input is tested without the real program.

Files: `KeePassXcKey.java`, `IdentityCreateCommand.java`, `IdentityRotateCommand.java`, `IdentityRevokeCommand.java`,
`IdentityChange.java` (print/refuse), `IdentityOperation.java` (the entry), `StandardInput.java` (the owner of the terminal);
tests `IdentityKeePassXcTest`, `IdentityRotateCommandTest`, `IdentityCreateCommandTest`, `KeePassXcStandIn`.
Overlap with the move of commit filing into `organization/git`: `IdentityRotateCommand`, `IdentityRevokeCommand`,
`IdentityChange`, `IdentityRotateCommandTest`, `RegistryCheckout`; this issue is built after that move or on its result, and
keeps its edits in those files to the lines named above.

## Acceptance

- Tests with the stand-in `keepassxc-cli` count one password prompt for a whole create, see a retry after
  `key-not-served` make no second key, and see no entry left after a refused attachment; each seen to fail first.
- The stand-in's check that no file holds the key at any time also catches a temporary file that is deleted before
  it looks (`IdentityKeePassXcTest`, `KeePassXcStandIn`); today it would miss one.
- The redirection of `keepassxc-cli`'s standard input to the person's terminal is tested; today only a run against
  the real keepassxc-cli 2.7.10 shows it.
- A refusal for standard error redirected away from the terminal, for a terminal that belongs to another account, and for an
  unreadable `/proc/self/fd` is answered before `keepassxc-cli` is started (the stand-in's `arguments` file is empty),
  with the same reason and the same exit code from `create`, `rotate` and `revoke --lost`; each seen to fail first.
- A master password that is not typed within the bound is answered `no-password` with exit code 64 and nothing kept, not
  as `key-store-missing`; seen to fail first (today a timeout is read as exit 127).
- After a refused attachment the database holds no entry of that path, or the answer names where the key is left (the
  recycle bin group by its name in that database); the stand-in database is read afterwards, not only the words.
- A re-run of `rotate` or `revoke --lost` with the same `--id` answers the `entry` of the first run, in the plain and in
  the JSON answer.
- A retry with the same `--entry` after `key-not-served` makes no second key: the fingerprint it waits for is the first
  run's, and the stand-in saw exactly one `add`.
- The stand-in's check that no file holds the key runs while the key exists: a test that writes the key to a temporary file
  and deletes it before `attachment-import` makes the check fail.
- Against the real `keepassxc-cli` 2.7.10 in a pseudo-terminal (not only the stand-in), one create asks the password once
  if step 2 is built as (a) or (b); if (c) is chosen, three, and the answer says so.

## Open questions

- How may one unlock serve a whole create? (a) `keepassxc-cli open` fed by Porelo after the person typed the password at
  `keepassxc-cli`'s own prompt: Porelo never sees the password, but the commands must be sent after the program has read it,
  and `open` reads its standard input as commands, so the timing has to be handled (measured: commands sent at once with the
  password are lost; with a pause they run). (b) A helper shell reads the password once and feeds each run: one prompt and no
  timing, but the master password passes through a process Porelo started, which the code comment says it never does.
  (c) Three prompts stay and the answer says so. Plan: (a), with (c) as the fall-back; say if (b) is acceptable.
- May a key that Porelo made for an entry it then removes stay in the database's recycle bin? KeePassXC's `rm` moves an
  entry there first (measured); removing it for good takes a second `rm` in the language of the database. Plan: remove for
  good, and say so; say if the recycle bin copy is acceptable, because it holds the private key until the person empties it.
- What bound is a master password to be waited for? The default kill is ten minutes; the plan says 120 s per prompt. A person
  with a hardware key (`--yubikey`) or a slow disk may need longer.

## To be checked

- A spike of step 2(a) in a pseudo-terminal against the real `keepassxc-cli` 2.7.10 (`script -qec`): whether the prompt for
  the password can be left to `keepassxc-cli` and the commands sent after it without a fixed pause (for instance by waiting
  for the `DATABASE>` prompt on its output). Not established; the piped measurement here had no terminal.
- What `hidepid` does to `/proc/self/fd/0` of the process itself (a process may read its own `/proc/<pid>/fd` under
  `hidepid=2`; whether `readlink` of it works for a process started by `sudo -u` or in a container is not established, and
  `/proc` here is mounted without `hidepid`). The refusal for it is written once this is measured, in a user namespace or
  with a remount on a VM.
- What `su` / `sudo -u` do to the wrapper's `exec 3<&0 <"$1"`: the other account cannot open the first account's pts (read,
  not run); measure on a VM with two accounts and see which message `sh` gives, so the refusal's words are the ones the
  person can act on.
- Whether a key read back by `attachment-export --stdout` is byte-identical to the one imported (OpenSSH armour with a
  trailing newline), so the fingerprint read back is the one the agent serves.
- The exit code and message of `rm` for a recycle bin in each language KeePassXC ships, and whether the bin can be switched
  off in a database (then `rm` deletes at once).
- Whether `IdentityOperation` can take an optional `entry` field without a change to the kept operation files other versions
  read (look at `IdentityOperation.read`).
- After the filing move into `organization/git` lands: which of the call-site lines named in the plan have moved.
