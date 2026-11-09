# stub

An in-memory, thread-safe planning adapter for Porelo's tests, under [LGPL-3.0-or-later](LICENSE). It provides an ordered workflow, two stories, two issues, atomic state changes, opaque revisions, refresh and independent watch subscriptions; it is neither published nor packaged.

Its executable is named `stub-planning`. Its main delegates to `PlanningMain.run`, using the same contract as an external adapter. See [writing a planning adapter](../../doc/planning-adapters.md).
