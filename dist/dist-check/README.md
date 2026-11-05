# dist-check

Reads the `.deb` and `.rpm` this build wrote - Porelo's and the stub agent's - and holds each to the version the
packaging must give it: the project's version with `-SNAPSHOT` as `~snapshot.<run><suffix>`, and the `.rpm`'s release
`1`. It runs only with `-Pdist`, after the packages are written, and needs `dpkg-deb` and `rpm`.

It also runs the native `porelo` this build wrote on a project file that does not read, and holds it to refusing with
the file's line: a class the YAML model needs and the native image left out reads in every JVM test and fails only
there.
