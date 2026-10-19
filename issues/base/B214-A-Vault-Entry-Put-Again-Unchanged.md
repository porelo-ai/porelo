# B214 — A Vault Entry Put Again Unchanged

**Status:** Idea.

**What must be true.** `porelo vault put NAME` given exactly the value the entry already holds says so and exits 0,
so a playbook that puts its keys on every run can report that nothing changed, without ever comparing a value
itself.

## Why

A host's second play puts each key of the machine file into the vault with `vault put --from-file`. A second `up` on
a host that is up must change nothing and say so (`changed=0`), but `vault put` answers `stored … (N characters)`
with exit 0 whether the entry was new, different or the same. A play must not read the value to compare, so it can
only call every `put` changed.

## The shape proposed

- Porelo compares inside the vault, against the entry it would overwrite. When they are the same it writes nothing
  and says `unchanged NAME`, with nothing of the value shown.
- `--json` gives `{"name", "stored"}`, `stored` false when the value was the same.
- The same for `--from-file` and standard input. A grant's entry, which holds no secret, compares by what it names.

## To be checked

- Whether the comparison can leak by its timing, and so must take constant time.
- Whether `doc/hosts.md` lists it among the answers a second run reads.
