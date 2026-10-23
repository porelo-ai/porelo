# B262 — A Stop's Survivors In The Form Its Interface Declares

**Status:** Idea.

**What must be true.** The daemon's `Stop` answers `surviving` in the type its interface declares, so a client that
decodes the declared reply sees what outlived the stop.

## Why

`org.fuin.porelo.Tasks1`'s `Stop` declares `surviving: int`, the number of helpers still alive. The daemon puts the
list of survivors there instead (`TaskMethods`), as `PanickedTask`'s and `Panic`'s `surviving: []string` do. A client
reading the declared integer cannot decode the list. porelo-frontend reads it as a number and takes anything else as
0, so the interface never shows that a single stop left something running. Since [B261](README.md#b261) names a
container that survives its stop there too, that case is not shown either.

Which form wins is for the analysis: the declared count with a list beside it under a new name, which keeps
`Tasks1`'s promise to existing clients, or the list the daemon already sends, with porelo-frontend reading it.
