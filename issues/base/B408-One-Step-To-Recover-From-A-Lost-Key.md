# B408 — One Step To Recover From A Lost Key

**Status:** Idea, for after 0.7: the operator's word of 2026-11-03. A lost key is an unlikely case, not needed for the near
future.
**Size:** L - a new command and its dialog, a listing across repositories, and several pushes that resume by id.
**Blocked by:** [B407](README.md#b407), or else the owner stands as admin and stays so.

**What must be true.** The person makes the new key and card, swaps the keys at the forge and hands the owner the card, and
that is all they do. The owner runs one step, *Recover*: Porelo lists what the old key signed, the owner confirms, may leave a
point out, and Porelo files the card and signs the rest anew by the owner's key. If it stops midway, the same id goes on.

**Today**, by commands, as the rehearsal of 2026-11-03 measured with no host: the owner's `org person replace` and `org project
admin`, `project address` by the admin, and a signed commit changing `project.yml` by plain git, for which no command exists. A
window shows only `an admin signs it anew`, not how.

**What the listing can and cannot say.** Porelo can list every commit by that key in the organization and the newest
change of `project.yml`. **It cannot tell which came after the theft:** a commit's date is what its signer wrote, so a thief can
backdate. Order on the line and what a machine had accepted are firm. So the listing shows that the key signed, dates marked as
claimed, with what each changed.

**The risk it must carry.** Signing all anew makes the owner vouch for what a thief wrote. The confirm therefore shows the
contents, and a point can be left out.

**Also to meet:**

- The same bytes must make a commit: today only `repository.yml` can, by a comment; `members.yml` is refused as changing nothing.
- Writing into a project's own repository takes write right at the forge. An address outside the owner's rights stays
  refused, and *Recover* says which; anyone with write can push a commit the owner signed.
- Host files the old key signed want a host admin, and an owner who lost their own key needs another owner.
- Whether a project on a host comes back after this has not been seen.
