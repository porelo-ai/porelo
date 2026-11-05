# B458 — A Refused Key Gets Help

**Status:** Backlog.
**Size:** M - five parts, below; the window is Agent Frontend's `F389`.

**What must be true.** A person whose push is refused for its key is led through it: told what happened and which key was used, shown
their keys, able to try one alone without writing anything, and to keep it for that address. A person who reads only an error has nothing to
go on.

The operator's choice, 2026-11-05. Nothing is built before the test.

## Found

The operator's test of `utils4j` on `0.5.1~snapshot.408`, forwarding approved work. The window showed git's raw line:

    'git --git-dir ... push --end-of-options git@github.com:fuinorg/utils4j.git ...' failed with exit code 128:
    ERROR: Permission to fuinorg/utils4j.git denied to deploy key

`porelo credentials list` named only `ssh://github.com/porelo-ai-test/`, so none was declared for that address, ssh chose a key by itself, and
it offered one GitHub knows as a deploy key without that right.

## What The Window Does

1. It says in plain words what happened and which key was used, by fingerprint and what the forge calls it, or that no credential is declared
   for the address.
2. It lists the person's keys: from the ssh-agent, so also from an open KeePassXC, and from files, reading no secret. Beside them: a key not
   listed? Open KeePassXC, unlock it, refresh.
3. "Try" tests one key alone, by a `git push --dry-run` with only that key, and writes nothing.
4. One click keeps it for that address and forwards again.
5. For what only a terminal can do, the window opens a terminal with the whole command ready; the person starts it. A terminal's own refusal
   gives the same words and that command.

## The Parts Here

- **The refusal's words, S.** For the window and the terminal: the key used, its fingerprint, the forge's word for it, or that none is declared
  and ssh chose by itself; and the command to run. To read first: where the raw line comes from, and whether a forward with no credential
  declared for the address should go on at all.
- **Naming one key of the agent, S.** Today `porelo credentials declare --agent` says "the agent", so the wrong key may still win. It names one by
  fingerprint or public key. The way to read first: the public half kept in a file of its own, named to ssh by `IdentityFile` with
  `IdentitiesOnly=yes`, which picks that key from the agent. Nothing secret is written. Not yet run here, and it must hold wherever a push is made,
  in a task too.
- **The list of keys, XS.** `SshKeys` already lists files by fingerprint and comment, with its daemon call. It gains the agent's, public halves only,
  and which of the two each is.
- **The dry run of one key, S.** A daemon call and a command: `git push --dry-run` with only that key, writing nothing, its answer in the same words.
- **Keeping it and forwarding again, XS.** `CredentialDeclare` with the key as above; the forward is then the call it already is.

The fields are agreed with Agent Frontend in the room after the test. Each warning names only what has been seen to work, by the operator's rule.

The rule beyond this case is [B459](README.md#b459).
