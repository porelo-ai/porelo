# testing

Fixtures shared by the modules' tests. Not published.

`src/keyring-test/java` is compiled as test code by `vault` and `apps/app`, which register the
keyring leak guard through JUnit autodetection. Keeping the source here avoids a dependency from
this module back to `vault`, whose tests already depend on this module.

The guard sets `porelo.keyring.prefix` to `porelo-test-<pid>` unless already configured and watches
only that JVM's prefix, so concurrent builds and real vault unlocks keep their own cached passphrases.
