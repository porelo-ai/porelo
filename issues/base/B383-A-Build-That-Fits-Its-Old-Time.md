# B383 — A Build That Fits Its Old Time

**Status:** Idea.
**Severity:** medium.
**Size:** S, proposed - the twenty slowest test classes of a Build measured, and each one's wait or real process
cut where a stand-in shows the same.

**What must be true.** The Build's `mvnw verify` takes about as long as it did before sprint 6m, not three times
as long. On 2026-10-31 it took 18 minutes (Build 41253673912, 23:43:26 to 00:01:41), where the build job's comment
named 5.5 minutes for a whole run; the job's bound was raised from 20 to 60 minutes so the Build runs at all. Not
measured which tests grew: real `git`, `ssh-agent`, `ssh-keygen` and `sleep` processes, and waits of a second and
more, came with this sprint's tests.

Found when the Build on 5eeb7662 was cancelled on 2026-11-01; not built tonight (operator's rule of 22:30Z).
