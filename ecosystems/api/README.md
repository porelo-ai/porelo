# api

The contract between Porelo and an ecosystem adapter, published as `porelo-ecosystem-api`: the interface an adapter implements, the server it runs and the client Porelo uses, over the varlink protocol `org.fuin.porelo.Ecosystem1`. Its `detect` says what an adapter recognizes of its tool in a directory, its `configure` answers the settings files that tool needs in a task. An adapter reports the contract version it was built against; below `EcosystemProtocol.LOWEST` it is refused, and any other is accepted, a newer one included. It starts one adapter with an empty environment, so nothing of Porelo's reaches it. Its discovery lists installed executables, keeping the first directory's copy per name and reporting failed descriptions alongside accepted adapters. It reads no build file.

Licensed under [Apache-2.0](LICENSE), unlike most of this repository, so an adapter of any license can build against it; it depends on nothing under the GPL or the LGPL alone.

See [writing an ecosystem adapter](../../doc/ecosystem-adapters.md).
