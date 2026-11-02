package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a fetch through a host's own gate uses. Harm guarded: a token that opens the forge as this
 * person goes to that gate alone, and shows in nothing.
 */
class HostForgeAccessTest {

    private static final String GATE = "http://127.0.0.1:3000/acme/demo.git";

    private static final String TOKEN = "token-of-the-person";

    @TempDir private Path dir;

    private final List<Command> commands = new ArrayList<>();

    private HostForgeAccess access(final @Nullable String fetchFrom, final CommandRunner helper) {
        return new HostForgeAccess(
                new PoreloContext(
                        command -> {
                            commands.add(command);
                            return helper.run(command);
                        },
                        new PoreloPaths(
                                XdgPaths.of(name -> dir.resolve(name).toString(), dir),
                                dir.resolve("bin")),
                        args -> 0,
                        name -> null),
                fetchFrom,
                FetchAccess.NONE);
    }

    private static CommandRunner answering(final int code, final String out) {
        return command -> new CommandResult(command, code, out, out);
    }

    @Test
    void it_asks_the_hosts_helper_with_nothing_on_its_input() {
        access(GATE, answering(0, TOKEN + "\n")).lend(GATE).close();

        assertThat(commands)
                .singleElement()
                .satisfies(
                        command -> {
                            assertThat(command.arguments())
                                    .containsExactly(
                                            "sudo",
                                            "-n",
                                            HostPerson.PROGRAM.toString(),
                                            "forge-token");
                            assertThat(command.input()).isEmpty();
                        });
    }

    @Test
    void the_token_goes_to_git_as_a_header_in_the_environment_and_only_there() {
        try (FetchAccess.Lease lease = access(GATE, answering(0, TOKEN + "\n")).lend(GATE)) {
            assertThat(lease.environment())
                    .containsEntry("GIT_CONFIG_COUNT", "2")
                    .containsEntry("GIT_CONFIG_KEY_0", "http.extraHeader")
                    .containsEntry(
                            "GIT_CONFIG_VALUE_0",
                            "Authorization: Basic "
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    ("x-access-token:" + TOKEN)
                                                            .getBytes(StandardCharsets.UTF_8)))
                    .containsEntry("GIT_CONFIG_KEY_1", "http.followRedirects")
                    .containsEntry("GIT_CONFIG_VALUE_1", "false")
                    .containsEntry("GIT_ALLOW_PROTOCOL", "http");
            assertThat(lease.arguments()).isEmpty();
            assertThat(Command.of("git").withEnvironment(lease.environment()).toString())
                    .as("a command's words, which a failure names")
                    .doesNotContain(TOKEN)
                    .doesNotContain(
                            Base64.getEncoder()
                                    .encodeToString(TOKEN.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://127.0.0.1:3000/acme/demo.git",
                "http://127.0.0.1/acme/demo.git",
                "http://127.0.0.1:3000",
                "http://127.0.0.1.example.org:3000/acme/demo.git",
                "http://localhost:3000/acme/demo.git",
                "http://me@127.0.0.1:3000/acme/demo.git",
                "http://127.0.0.2:3000/acme/demo.git",
                "http://127.0.0.1:3000/acme/demo.git?x=1",
                "git@forge.example.org:acme/demo.git",
                "https://forge.example.org/acme/demo.git"
            })
    void no_other_shape_of_address_asks_though_the_host_file_names_it(final String address) {
        final HostForgeAccess access = access(address, answering(0, TOKEN));

        assertThat(access.asksFor(address)).isFalse();
        assertThat(access.lend(address)).isSameAs(FetchAccess.Lease.EMPTY);
        assertThat(commands).isEmpty();
    }

    @Test
    void the_gate_asks_nothing_when_the_host_file_names_another_or_none() {
        final String other = "http://127.0.0.1:4000/acme/demo.git";

        assertThat(access(other, answering(0, TOKEN)).lend(GATE)).isSameAs(FetchAccess.Lease.EMPTY);
        assertThat(access(null, answering(0, TOKEN)).lend(GATE)).isSameAs(FetchAccess.Lease.EMPTY);
        assertThat(commands).isEmpty();
    }

    @Test
    void sixty_five_is_a_refusal_and_it_holds_nothing_the_helper_said() {
        assertThatThrownBy(() -> access(GATE, answering(65, TOKEN)).lend(GATE))
                .isInstanceOfSatisfying(
                        HostForgeAccess.Denied.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo(65);
                            assertThat(ex.reason()).isEqualTo("refused-state");
                            assertThat(ex.getMessage()).doesNotContain(TOKEN);
                        });
    }

    @ParameterizedTest
    @ValueSource(ints = {69, 1, 64, 73, 126})
    void anything_else_cannot_say_and_it_holds_nothing_the_helper_said(final int code) {
        assertThatThrownBy(() -> access(GATE, answering(code, TOKEN)).lend(GATE))
                .isInstanceOfSatisfying(
                        HostForgeAccess.Denied.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo(69);
                            assertThat(ex.reason()).isEqualTo("cannot-say");
                            assertThat(ex.getMessage()).doesNotContain(TOKEN);
                        });
    }

    @Test
    void a_helper_that_cannot_run_cannot_say() {
        final CommandRunner absent =
                command -> {
                    throw new CommandException(command, new java.io.IOException("no sudo"));
                };

        assertThatThrownBy(() -> access(GATE, absent).lend(GATE))
                .isInstanceOfSatisfying(
                        HostForgeAccess.Denied.class, ex -> assertThat(ex.code()).isEqualTo(69));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "   \n", "two\nlines\n", "with space\n", "evil\r\nX: y"})
    void what_is_no_single_token_cannot_say_rather_than_becoming_a_header(final String out) {
        assertThatThrownBy(() -> access(GATE, answering(0, out)).lend(GATE))
                .isInstanceOfSatisfying(
                        HostForgeAccess.Denied.class, ex -> assertThat(ex.code()).isEqualTo(69));
    }
}
