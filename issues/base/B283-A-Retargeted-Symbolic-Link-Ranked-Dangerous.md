# B283 — A Retargeted Symbolic Link Ranked Dangerous

**Status:** Idea, filed on the operator's word.

**What must be true.** A change that points an existing symbolic link somewhere else is ranked dangerous in the review,
as a new symbolic link is, and is never filed as generated or reformatting.

## Why

`ReviewRanking.danger` ranks a symbolic link dangerous only when it is new (mode `120000` now, another mode before). A
commit that changes only an existing link's target keeps mode `120000` on both sides and is ranked ordinary or
reformatting, though a link's target decides what a later reader or build follows.
