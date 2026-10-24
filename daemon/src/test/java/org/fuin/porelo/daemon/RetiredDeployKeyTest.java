package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.FollowedProjects;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Keeps the retired method callable without creating access at a forge. */
class RetiredDeployKeyTest {
    @ParameterizedTest
    @CsvSource({
        "ssh://git@forge.example/team/repo.git, forge.example, unknown",
        "https://forge.example/team/repo.git, forge.example, unknown",
        "'', <host>, unknown",
        "/srv/git/repo.git, <host>, unknown",
        "ssh://git@ignored.example/team/repo.git, own.example, own",
        "'', work.example, work",
        "'', followed.example, followed"
    })
    void theRetiredMethodRefusesEvenWhenItCouldMakeAKey(
            String upstream, String host, String source, @TempDir Path dir) throws Exception {
        // A legacy client must not keep creating write-capable keys after their retirement.
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final char[] passphrase = "test passphrase".toCharArray();
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(xdg, dir.resolve("bin")),
                                args -> 0)
                        .asking(prompt -> passphrase.clone());
        context.vault().write(Map.of(), passphrase);
        context.openerAsking(false, new PrintWriter(new StringWriter()));
        if (!source.equals("unknown")) {
            final Path file =
                    source.equals("followed")
                            ? context.paths()
                                    .projects()
                                    .followedClone("planning")
                                    .resolve("project.yml")
                            : dir.resolve("project.yml");
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    "project:\n  name: planning\n  security_class: guarded\n"
                            + (source.equals("own")
                                    ? "  upstream: ssh://git@own.example/team/repo.git\n"
                                    : "")
                            + "image: { base_image: \"ubuntu:24.04\" }\n"
                            + "repositories:\n  work:\n    upstream: ssh://git@work.example/team/repo.git\n");
            Files.createDirectories(context.paths().projects().projectRegistry());
            Files.writeString(
                    context.paths().projects().projectRegistry().resolve("planning"), file + "\n");
            if (source.equals("followed")) {
                new FollowedProjects(context.paths().projects().followed())
                        .follow("planning", "ssh://git@followed.example/team/repo.git", true);
            }
        }
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("daemon.sock"))) {
            Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final VarlinkException refused =
                        catchThrowableOfType(
                                VarlinkException.class,
                                () ->
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".DeployKey",
                                                Map.of(
                                                        "project",
                                                        "planning",
                                                        "upstream",
                                                        upstream,
                                                        "repository",
                                                        source.equals("work") ? "work" : "")));
                assertThat(refused)
                        .as("every retired call must refuse instead of creating a key")
                        .isNotNull();
                assertThat(refused.getErrorName())
                        .as("the retained Tasks1 error")
                        .isEqualTo(PoreloDaemon.INTERFACE + ".DeployKeyRefused");
                assertThat(refused.getParameters())
                        .as("the caller is directed to the person's credential for this host")
                        .containsEntry(
                                "message",
                                "Porelo makes no deploy keys; git reaches the repository with your own ssh key (an ssh address) or your token stored as git.token."
                                        + host
                                        + " (an https address)");
                assertThat(context.vault().read(passphrase))
                        .as("retirement must leave no new credential")
                        .isEmpty();
                assertThat(context.credentialRegistry().all())
                        .as("retirement must leave no new declaration")
                        .isEmpty();
            }
        }
    }
}
