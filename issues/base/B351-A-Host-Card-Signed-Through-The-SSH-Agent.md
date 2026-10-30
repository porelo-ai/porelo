# B351 — A Host Card Signed Through The SSH Agent

**Status:** Idea.

**What must be true.** `porelo card make-host` signs a host card through `ssh-agent` with the host admin's key, as
`ssh-keygen -Y sign` does with a key the agent holds: KeePassXC's agent included. No private key file is read and
none is written. `--key` names the public key, which picks the agent's key to sign with. An agent that holds no such
key, or refuses to sign, is refused, naming the key's fingerprint. An unencrypted key file still works as before.

**Why.** A host admin whose key lives only in KeePassXC cannot sign their host's card today without writing the
private key to disk, and that breaks the promise of one key per person, kept where they keep it. For the sprint 6m
test, walk6m's host card is signed with a throwaway host-admin key instead.

Filed at the Coordinator's word (2026-10-30), when [B349](README.md#b349) was found to sign from a key file alone. Not
planned in a sprint; whether it belongs in 6m is the operator's decision.

## Open questions

- Does it belong in sprint 6m, since it touches the one-key promise?
