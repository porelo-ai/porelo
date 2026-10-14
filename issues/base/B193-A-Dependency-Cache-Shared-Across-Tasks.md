# B193 — A Dependency Cache Shared Across Tasks

**Status:** Accepted.

**What must be true.** The dependencies a task downloads - Maven, npm, pub, pip - are cached once for a project's
tasks and handed into each task read-only, so the next task does not download them again; what a task installs or
builds itself stays its own and never reaches another task through the cache.

From [PJ39](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj39), which replaces the split `~/.m2` of today's host sessions with it.
