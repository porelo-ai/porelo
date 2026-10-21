# B237 — A Name Granted Live Through A CNAME

**Status:** Idea.

**What must be true.** A name granted to a running task is reached as it is when the project names it at the start,
also when the name answers through a CNAME.

## Why

Measured in a task on the VM (`0.5.1~snapshot.307`, agent `stub`): `fuinorg.jfrog.io` answers through a CNAME
(`endpointdns-gcprod-2-use1-inc-lb.jfrog.io`). Granted to the running task with `porelo shield egress --task
--add-domain`, it resolved, but every connection was dropped (`porelo-drop` in `events.jsonl`, curl timed out after
40 s), even with the CNAME's target granted too. Named in the project file at the task's start, the same fetch
answered 200 in 0.6 s. A name without a CNAME (`github.com`) worked when granted live.
