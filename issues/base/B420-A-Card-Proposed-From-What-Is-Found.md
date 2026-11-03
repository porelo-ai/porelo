# B420 — A Card Proposed From What Is Found

**Status:** Review.
**Size:** S - a command that reads and writes nothing, and a test of a signing-only key.

**What must be true, by the operator's word of 2026-11-03.** *Make my card* asks only what is not found or not unique.
`porelo identity propose --json` writes nothing and answers `login`, `name`, `email` and `key`, each with its source, what is
`missing`, and the `ssh-add` line for a key not in the agent. Login from a token or `ssh -T git@github.com`. Key, in this order:
`git config user.signingkey` when it names an SSH key, then a key in the agent the forge lists as a signing key, then one it
lists as an authentication key. Name and email from `git config`, then the forge's public profile. Porelo reads no private key.

A person whose key is only a signing key is proven by a test: filing the card, vouching and pushing with a second key.
