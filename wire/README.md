# wire

The records that cross a boundary - between processes, the host and a task - and the varlink and socket labelling
they travel with.

It also carries the daemon's contract, `org.fuin.porelo.Tasks1`, as resources under
`src/main/resources/varlink/org.fuin.porelo.Tasks1/`: one `.varlink` file per area, and `parts`, the order in which
the daemon serves them as one interface description. A client outside porelo reads the contract from this jar.

Licensed under [Apache-2.0](LICENSE), unlike most of this repository, so that an ecosystem adapter of any license can build on it.
