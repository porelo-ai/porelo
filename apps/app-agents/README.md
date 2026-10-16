# app-agents

The command line's and the daemon's area for agents and providers, in the package `org.fuin.porelo.app` like every area. It is one
layer of the `porelo` executable, which [app](../app/README.md) assembles; it is not a program of its own.

- Daemon methods: `AgentMethods`; their contract is `40-agent` in
  `daemon/src/main/resources/varlink/org.fuin.porelo.Tasks1/`. File locations: `AgentPaths`.
- Depends on `app-base` and no other area, as AGENTS.md's area table sets out.
