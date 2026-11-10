package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostGatherConfigTest {
    private static final Path SOURCE = Path.of("/home/alice/.config/porelo-host/push/acme.conf");
    private static final String LOCAL = "http://127.0.0.1:3001";
    private static final String VALID =
            "# configuration\nlocal "
                    + LOCAL
                    + "\nowner acme\ncentral-ca /etc/central.pem\nrepository first https://central.example/acme/first.git\nrepository second https://central.example/acme/second.git\n";

    private HostGatherConfig parse(String text) {
        return HostGatherConfig.parse(SOURCE, text, "acme", LOCAL);
    }

    @Test
    void selectionKeepsConfiguredOrExplicitOrder() {
        // Selection must preserve every requested repository and its order.
        final var config = parse(VALID);
        assertThat(config.select(List.of()))
                .extracting(HostGatherConfig.Repository::name)
                .containsExactly("first", "second");
        assertThat(config.select(List.of("second", "first")))
                .extracting(HostGatherConfig.Repository::name)
                .containsExactly("second", "first");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "local http://elsewhere.example",
                "local",
                "local http://127.0.0.1:3001 extra",
                "owner acme extra",
                "owner elsewhere",
                "central-ca relative",
                "central-ca /etc/central.pem extra",
                "repository ../escape https://central.example/repo",
                "repository first http://central.example/repo",
                "repository first https://user@central.example/repo",
                "repository first https://central.example/repo?token=x",
                "repository first https://central.example/repo#fragment",
                "repository first https://central.example/repo extra",
                "repository first https://central.example/repo\nrepository first https://central.example/other",
                "execute /usr/bin/id"
            })
    void malformedSettingsCannotChooseAnUnintendedDestination(String line) {
        // Malformed config must not redirect a credential or repository.
        final String key = line.split(" ", 2)[0];
        final String text =
                VALID.lines()
                                .filter(value -> !value.startsWith(key + " "))
                                .collect(java.util.stream.Collectors.joining("\n"))
                        + "\n"
                        + line
                        + "\n";
        assertThatThrownBy(() -> parse(text))
                .isInstanceOfSatisfying(
                        HostGatherRefusal.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(65));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "owner", "central-ca", "repository"})
    void incompleteConfigurationRefuses(String key) {
        // Incomplete config must not select a guessed destination or omit work.
        final String text =
                VALID.lines()
                        .filter(line -> !line.startsWith(key + " "))
                        .collect(java.util.stream.Collectors.joining("\n"));
        assertThatThrownBy(() -> parse(text))
                .isInstanceOfSatisfying(
                        HostGatherRefusal.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(65));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "first,first", "../escape", "-option", "@file"})
    void unsafeSelectionRefuses(String names) {
        // Unsafe repository names must not reach credential-bearing Git.
        final var config = parse(VALID);
        assertThatThrownBy(() -> config.select(List.of(names.split(","))))
                .isInstanceOfSatisfying(
                        HostGatherRefusal.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(64));
    }
}
