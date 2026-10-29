# B327 — An Unfollow Retires The Accounts Of An Access Project

**Status:** Idea.

**What must be true.** `project unfollow` and `project clear` of a project whose access file lies in a repository of
its own retire each of its tasks' transport accounts with the settings of the access file applied until then, as
they do for a project whose settings are in `project.yml`; none is left on the conversation server.

Found in the review of the access file (2026-10-29): the unfollow deletes the follow record before it removes the
tasks, so each task's `retire` finds the project no longer followed, its access file withheld, and says that the
task's account is not removed. The vault keeps its secrets, so nothing is lost but the account stays.

## Acceptance

- An unfollow of a followed project with `access: {repository: ...}` applied and one task enrolled runs the
  transport's `retire` for that task with the applied settings, and says nothing is left.
- An unfollow of such a project whose access file is not applied retires nothing and says why, as now.

## Open questions

- Take the settings before the follow record goes and hand them to the removal, or remove the tasks before the
  record, which changes the order the unfollow keeps under the clone's lock.
