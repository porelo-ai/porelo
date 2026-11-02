# app-organization

The command line's area for the organization of the new structure, in the package `org.fuin.porelo.app` like every area:
`porelo org` and a project's new verbs. It is a layer of the `porelo` executable, which [app](../app/README.md) assembles; it is
not a program of its own.

- It answers through `organization/api`, over [git-tree](../../organization/git-tree/README.md), and depends on `app-base` and
  `app-model`, never on another area.
- Its answers are a contract the interface reads: field names, reasons and exit codes change only by agreement.
