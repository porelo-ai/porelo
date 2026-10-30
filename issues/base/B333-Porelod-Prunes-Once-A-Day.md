# B333 — Porelod Prunes Once A Day

**Status:** Idea.

**What must be true.** `porelod` cleans up once a day by itself, with no one running `porelo prune`. Its daily
run removes what the hand-run `porelo prune --yes` removes. It also removes dangling images, Porelo images that no
running task uses and a newer build has replaced, and volumes no container uses. As with `prune`, whatever holds
work stays.

Asked by the operator (2026-10-30): the development machine held 286 Podman images (41 GB) and 630 volumes (11 GB),
and hardly any of them were in use. Today only `porelo prune` removes anything, only when run by hand, and among
images only those named after projects that no longer exist. That leaves out:

- dangling images left behind when a project that still exists is built again;
- Porelo images that no running task uses and that a newer build has replaced;
- volumes without a container.

## Acceptance

- Each of the three kinds above, placed on a machine, is gone after the daily run. A running task's image, the
  newest build of a followed project and a volume a container uses all stay.
- Unreviewed work stays as it does for `prune`: an unreviewed push, a stopped task's workspace, a held or unsent
  message.
- The run happens with no command typed, at most once a day.
- `porelo prune` lists the three new kinds too.
