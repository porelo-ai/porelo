package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.testing.FakeCommandRunner;

/** Supplies namespace program paths without consulting or changing the test machine. */
class TransportTestRunner extends FakeCommandRunner implements TransportVerbs.Programs {

    @Override
    public Function<String, Path> programs() {
        return name -> Path.of("/usr/bin", name);
    }

    /** Resolves fixture names without consulting the machine's network. */
    @Override
    public TransportFence.Resolver resolver() {
        return name -> new InetAddress[] {InetAddress.ofLiteral("192.0.2.10")};
    }

    /** Nameservers are fixture data just like saved transport reaches. */
    @Override
    public Path resolvConf() {
        return resolverFile();
    }

    private static Path resolverFile() {
        return Path.of("src", "test", "resources", "transport-resolv.conf");
    }

    /** Supplies tool paths for a custom fake without changing its command behavior. */
    static CommandRunner fake(final CommandRunner runner) {
        return supplying(runner, name -> Path.of("/usr/bin", name));
    }

    /** Keeps real subprocess/input tests local while standing in for pasta, nft and setpriv. */
    static CommandRunner processes(final CommandRunner runner, final Path directory) {
        final String passOn = "while [ \"$1\" != -- ]; do shift; done\nshift\nexec \"$@\"\n";
        final Path pasta = executable(directory.resolve("pasta"), passOn);
        final Path nft = executable(directory.resolve("nft"), "cat > /dev/null\n");
        final Path setpriv = executable(directory.resolve("setpriv"), passOn);
        return supplying(
                runner,
                name ->
                        switch (name) {
                            case "pasta" -> pasta;
                            case "nft" -> nft;
                            case "setpriv" -> setpriv;
                            case "sh" -> Path.of("/bin/sh");
                            default ->
                                    throw new IllegalArgumentException("Unexpected tool: " + name);
                        });
    }

    private static CommandRunner supplying(
            final CommandRunner runner, final Function<String, Path> programs) {
        return new TransportVerbs.Programs() {
            @Override
            public Function<String, Path> programs() {
                return programs;
            }

            @Override
            public TransportFence.Resolver resolver() {
                return name -> new InetAddress[] {InetAddress.ofLiteral("192.0.2.10")};
            }

            @Override
            public Path resolvConf() {
                return resolverFile();
            }

            @Override
            public CommandResult run(final Command command) {
                return runner.run(command);
            }

            @Override
            public CommandResult run(final Command command, final Consumer<String> lines) {
                return runner.run(command, lines);
            }
        };
    }

    private static Path executable(final Path file, final String body) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "#!/bin/sh\n" + body);
            if (!file.toFile().setExecutable(true))
                throw new IOException("Cannot make fixture executable");
            return file;
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
