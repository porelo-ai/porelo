# cpu-check - `porelo-cpu-check`

Checks that a native executable runs on any x86-64 CPU, which `-march=x86-64` in the shared native build arguments
is there for, and that GraalVM CE 25.1 to 25.5, whose Base64 decoder overflows on a malformed string, did not build
it. It builds nothing itself.
