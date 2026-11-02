package org.fuin.porelo.app;

import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;

/** The context of a test that only walks or parses the command tree: nothing in it is there. */
final class NoAccount {

    private NoAccount() {}

    /**
     * Returns the context.
     *
     * @return A context whose directories nothing makes.
     */
    static PoreloContext context() {
        final Path nowhere = Path.of("/nonexistent/porelo-no-account");
        return new PoreloContext(
                new FakeCommandRunner(),
                new PoreloPaths(XdgPaths.of(name -> null, nowhere), nowhere.resolve("bin")),
                arguments -> 0);
    }
}
