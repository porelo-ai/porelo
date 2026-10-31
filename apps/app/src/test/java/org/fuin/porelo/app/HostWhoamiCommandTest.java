package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Guards what an interface learns of the account its SSH reached. Harm: a tunnel returned to a
 * socket nothing answers on, or an account taken for another person's, while the answer says all is
 * well.
 */
class HostWhoamiCommandTest {

    @TempDir Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context() {
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
                        dir.resolve("home"));
        return new PoreloContext(
                new ProcessCommandRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                args -> 0,
                name -> null);
    }

    private int whoami(PoreloContext context) {
        final CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        return cli.execute("host", "whoami", "--json");
    }

    @Test
    void answers_the_account_it_runs_in_and_the_socket_its_daemon_answers_on() throws Exception {
        final PoreloContext context = context();
        final Path socket = context.paths().daemonSocket();
        Files.createDirectories(socket.getParent());
        try (VarlinkServer daemon = new VarlinkServer(socket, "org.example.Test")) {
            Thread.ofVirtual().start(daemon);

            assertThat(whoami(context)).as(err.toString()).isZero();
        }

        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString());
        assertThat(new ArrayList<Object>(answer.keySet()))
                .containsExactly("login", "uid", "return_socket");
        final ProcessCommandRunner runner = new ProcessCommandRunner();
        assertThat(answer.get("login"))
                .as("the account's own login")
                .isEqualTo(runner.runOrFail(Command.of("id", "-un")).trimmedOutput());
        assertThat(((Number) answer.get("uid")).longValue())
                .isEqualTo(
                        Long.parseLong(runner.runOrFail(Command.of("id", "-u")).trimmedOutput()));
        assertThat(answer.get("return_socket"))
                .as("where the daemon that answered listens")
                .isEqualTo(socket.toString());
    }

    @Test
    void a_socket_file_no_daemon_answers_on_is_no_daemon() throws Exception {
        final PoreloContext context = context();
        final Path socket = context.paths().daemonSocket();
        Files.createDirectories(socket.getParent());
        Files.writeString(socket, "left behind");

        assertThat(whoami(context)).isEqualTo(66);

        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString());
        assertThat(new ArrayList<Object>(answer.keySet())).containsExactly("reason", "words");
        assertThat(answer.get("reason")).isEqualTo("no-daemon");
    }
}
