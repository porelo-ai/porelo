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
    void eitherRepositoryNameSelectsTheSameDestination() {
        // A project key must release the same approved repository as its forge name.
        final var config = parse(VALID.replace("repository first ", "repository first component "));
        assertThat(config.select(List.of("component")))
                .as("the project key selects its forge destination")
                .isEqualTo(config.select(List.of("first")));
    }

    @Test
    void collidingRepositoryNamesRefuseInsteadOfChoosingADestination() {
        // An alias collision must never release a different repository by accident.
        final var config = parse(VALID.replace("repository first ", "repository first second "));
        assertThatThrownBy(() -> config.select(List.of("second")))
                .as("both matching destinations must be named for repair")
                .isInstanceOf(HostGatherRefusal.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("second/first")
                .hasMessageContaining("second/second");
        assertThatThrownBy(() -> config.select(List.of()))
                .as("selecting all cannot bypass an ambiguous configuration")
                .isInstanceOf(HostGatherRefusal.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void selectingBothNamesCannotTransferTheRepositoryTwice() {
        // Selecting both aliases must not write the same remote twice.
        final var config = parse(VALID.replace("repository first ", "repository first component "));
        assertThatThrownBy(() -> config.select(List.of("component", "first")))
                .as("aliases are deduplicated by destination before any transfer")
                .isInstanceOf(HostGatherRefusal.class)
                .hasMessageContaining("twice")
                .hasMessageContaining("component/first");
    }

    @Test
    void severalProjectKeysForOneForgeRepositoryResolveToOneTransfer() {
        // Repeated verified aliases must neither lose a usable key nor release one repository
        // twice.
        final var config =
                parse(
                        VALID.replace(
                                "repository first https://central.example/acme/first.git",
                                "repository first component https://central.example/acme/first.git\nrepository first module https://central.example/acme/first.git"));
        assertThat(config.select(List.of("component")))
                .as("each key resolves to the one forge repository")
                .isEqualTo(config.select(List.of("module")))
                .isEqualTo(config.select(List.of("first")));
        assertThat(config.select(List.of())).as("all means each repository once").hasSize(2);
        assertThatThrownBy(() -> config.select(List.of("component", "module")))
                .as("two keys cannot cause two writes")
                .isInstanceOf(HostGatherRefusal.class)
                .hasMessageContaining("twice");
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
