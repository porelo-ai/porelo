# api

The contract between Porelo and a build-tool plugin, published as `porelo-ecosystem-api`: the interface a plugin implements, the server it runs and the client Porelo uses, over the varlink protocol `org.fuin.porelo.Ecosystem1`. Its `detect` says what a plugin recognizes of its tool in a directory, its `configure` answers the settings files that tool needs in a task. A plugin reports the contract version it was built against; below `EcosystemProtocol.LOWEST` it is refused, and any other is accepted, a newer one included. It starts one plugin with an empty environment, so nothing of Porelo's reaches it. Its discovery lists installed executables, keeping the first directory's copy per name and reporting failed descriptions alongside accepted plugins. It reads no build file.

Licensed under [Apache-2.0](LICENSE), unlike most of this repository, so a plugin of any license can build against it; it depends on nothing under the GPL or the LGPL alone.

See [writing a build-tool plugin](../../doc/ecosystem-plugins.md).
