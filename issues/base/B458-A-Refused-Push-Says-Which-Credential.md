# B458 — A Refused Push Says Which Credential

**Status:** Backlog.
**Size:** S - the push's refusal, with what is declared for the address.

**What must be true.** A push refused upstream says which credential Porelo gave it, or that none is declared for that address, and what to do.

The operator's word, 2026-11-05: after the test.

## Found

The operator's test of `utils4j` on `0.5.1~snapshot.408`, forwarding approved work. The window showed git's raw line:

    'git --git-dir ... push --end-of-options git@github.com:fuinorg/utils4j.git ...' failed with exit code 128:
    ERROR: Permission to fuinorg/utils4j.git denied to deploy key

`porelo credentials list` named only `ssh://github.com/porelo-ai-test/`, so none was declared for that address, ssh chose a key by itself, and
it offered one GitHub knows as a deploy key without that right.

## What Is Asked

- The refusal names the credential used, or says none is declared for the address and that ssh chose by itself.
- It names `porelo credentials declare` and `porelo credentials check`, as far as they were seen to work for this case.
- A field an interface can show in place of the raw command, which Agent Frontend then does.

To read first: where the raw line comes from, and whether a forward with no credential declared for the address should go on at all.
