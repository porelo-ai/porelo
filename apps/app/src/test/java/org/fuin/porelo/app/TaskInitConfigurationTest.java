package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskInitConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"containers", "engine"})
    void aConfiguredValidInitIsNotRefused(String table, @TempDir Path root) throws IOException {
        // Refusing a usable custom init blocks a task Podman can run.
        assertThat(check(root, Map.of())).as("a known missing default still refuses").isPresent();
        final Path init = executable(root.resolve("custom-\"init"));
        conf(
                root.resolve("home/containers/containers.conf"),
                "[" + table + "]\ninit_path = '" + init + "'\n");

        assertThat(check(root, Map.of()))
                .as("a configured executable replaces the missing default")
                .isEmpty();
        conf(
                root.resolve("home/containers/containers.conf"),
                "["
                        + table
                        + "]\ninit_path = \""
                        + init.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\" # escaped quote\n");
        assertThat(check(root, Map.of()))
                .as("basic strings decode escaped path characters")
                .isEmpty();
    }

    @Test
    void aConfiguredMissingInitIsRefusedByItsPath(@TempDir Path root) throws IOException {
        // A default init must not hide a broken configured path until after task preparation.
        final Path missing = root.resolve("missing-init");
        conf(
                root.resolve("home/containers/containers.conf"),
                "[containers]\ninit_path = '" + missing + "'\n");
        executable(root.resolve("legacy"));

        assertThat(check(root, Map.of()))
                .as("the refusal names the init Podman will actually use")
                .hasValueSatisfying(reason -> assertThat(reason).contains(missing.toString()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnreadableConfigurationStandsAside(boolean directory, @TempDir Path root)
            throws IOException {
        // Guessing defaults after a failed read can refuse a valid configured task.
        final Path file = root.resolve("home/containers/containers.conf");
        if (directory) {
            Files.createDirectories(file);
        } else {
            conf(file, "[containers\ninit_path = 'unfinished\n");
        }

        assertThat(check(root, Map.of()))
                .as("an I/O or parse failure leaves the decision to Podman")
                .isEmpty();
    }

    private static Optional<String> check(Path root, Map<String, String> environment) {
        return TaskInit.missing(
                new ProcessCommandRunner(),
                List.of(root.resolve("legacy")),
                "",
                root,
                root.resolve("home"),
                environment::get);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "usr/share/containers/containers.conf",
                "etc/containers/containers.conf",
                "etc/containers/containers.conf.d/20-init.conf",
                "home/containers/containers.conf",
                "home/containers/containers.conf.d/20-init.conf"
            })
    void everyRootlessConfigurationLocationCanSupplyAnInit(String location, @TempDir Path root)
            throws IOException {
        // Missing any rootless configuration location can block a machine's usable init.
        final Path init = executable(root.resolve("custom-init"));
        conf(root.resolve(location), "[containers]\ninit_path = '" + init + "'\n");

        assertThat(check(root, Map.of())).as("the init supplied by %s", location).isEmpty();
        final Path missing = root.resolve("missing-init");
        conf(root.resolve(location), "[containers]\ninit_path = '" + missing + "'\n");
        assertThat(check(root, Map.of()))
                .as("the same location must be interpreted, not merely deferred")
                .hasValueSatisfying(reason -> assertThat(reason).contains(missing.toString()));
    }

    @Test
    void laterFilesAndSortedDropInsWinWithinTheSameTable(@TempDir Path root) throws IOException {
        // A stale earlier setting must not hide the init that Podman ultimately uses.
        final Path valid = executable(root.resolve("valid"));
        final Path missing = root.resolve("last-missing");
        conf(
                root.resolve("usr/share/containers/containers.conf"),
                "[containers]\ninit_path = '" + valid + "'\n");
        conf(
                root.resolve("etc/containers/containers.conf"),
                "[containers]\ninit_path = '/missing-system'\n");
        conf(
                root.resolve("etc/containers/containers.conf.d/30-init.conf"),
                "[containers]\ninit_path = '" + valid + "'\n");
        conf(
                root.resolve("home/containers/containers.conf"),
                "[containers]\ninit_path = '" + valid + "'\n");
        conf(
                root.resolve("home/containers/containers.conf.d/90-last.conf"),
                "[containers]\ninit_path = '" + missing + "'\n");
        conf(
                root.resolve("home/containers/containers.conf.d/10-first.conf"),
                "[containers]\ninit_path = '" + valid + "'\n");
        conf(root.resolve("home/containers/containers.conf.d/99-ignored.txt"), "not TOML");
        executable(root.resolve("legacy"));

        assertThat(check(root, Map.of()))
                .as("the last .conf wins, not creation order or .txt files")
                .hasValueSatisfying(reason -> assertThat(reason).contains(missing.toString()));
    }

    @Test
    void containersInitWinsOverAnEngineInitInALaterFile(@TempDir Path root) throws IOException {
        // Confusing table precedence can approve a start whose selected init is missing.
        final Path missing = root.resolve("containers-missing");
        final Path valid = executable(root.resolve("engine-valid"));
        conf(
                root.resolve("etc/containers/containers.conf"),
                "[containers]\ninit_path = '" + missing + "'\n");
        conf(
                root.resolve("home/containers/containers.conf"),
                "[engine]\ninit_path = '" + valid + "'\n");
        executable(root.resolve("legacy"));

        assertThat(check(root, Map.of()))
                .as("containers.init_path has priority over engine.init_path")
                .hasValueSatisfying(reason -> assertThat(reason).contains(missing.toString()));
    }

    @Test
    void containersConfReplacesSystemAndUserConfiguration(@TempDir Path root) throws IOException {
        // Reading an ignored default file can wrongly refuse a usable override.
        final Path init = executable(root.resolve("custom-init"));
        conf(root.resolve("usr/share/containers/containers.conf"), "unparsable system file");
        conf(
                root.resolve("home/containers/containers.conf"),
                "[containers]\ninit_path='/missing-user'\n");
        final Path selected = root.resolve("selected.conf");
        conf(selected, "[containers]\ninit_path = '" + init + "'\n");

        assertThat(check(root, Map.of("CONTAINERS_CONF", selected.toString())))
                .as("CONTAINERS_CONF is read alone instead of the normal layers")
                .isEmpty();
        final Path missing = root.resolve("selected-missing");
        conf(selected, "[containers]\ninit_path = '" + missing + "'\n");
        assertThat(check(root, Map.of("CONTAINERS_CONF", selected.toString())))
                .as("an ignored malformed system file cannot force a stand-aside")
                .hasValueSatisfying(reason -> assertThat(reason).contains(missing.toString()));
    }

    @Test
    void containersConfOverrideIsReadLastEvenWithContainersConf(@TempDir Path root)
            throws IOException {
        // Ignoring the final override can refuse a valid init or approve a missing one.
        final Path init = executable(root.resolve("custom-init"));
        final Path missing = root.resolve("override-missing");
        final Path selected = root.resolve("selected.conf");
        final Path override = root.resolve("override.conf");
        conf(selected, "[engine]\ninit_path = '" + missing + "'\n");
        conf(override, "[engine]\ninit_path = '" + init + "'\n");
        final Map<String, String> environment =
                Map.of(
                        "CONTAINERS_CONF",
                        selected.toString(),
                        "CONTAINERS_CONF_OVERRIDE",
                        override.toString());

        assertThat(check(root, environment))
                .as("the final override replaces the selected file")
                .isEmpty();
        conf(override, "[engine]\ninit_path = '" + missing + "'\n");
        assertThat(check(root, environment))
                .as("a missing init in the override is named")
                .hasValueSatisfying(reason -> assertThat(reason).contains(missing.toString()));
    }

    @Test
    void configuredHelperDirectoriesReplaceDefaultsAndAllowPathFallback(@TempDir Path root)
            throws IOException {
        // Checking an unrelated default helper can both block valid tasks and hide missing inits.
        final Path helper = executable(root.resolve("helpers/catatonit"));
        final Path file = root.resolve("home/containers/containers.conf");
        conf(
                file,
                "[engine]\nhelper_binaries_dir = [\n '"
                        + helper.getParent()
                        + "', # custom only\n]\n");

        assertThat(check(root, Map.of()))
                .as("the configured helper replaces default directories")
                .isEmpty();
        Files.delete(helper);
        assertThat(
                        TaskInit.missing(
                                new ProcessCommandRunner(),
                                List.of(),
                                "",
                                root,
                                root.resolve("home"),
                                Map.<String, String>of()::get))
                .as("configured helper lookup needs no default candidates")
                .isPresent();
        final Path unrelated = executable(root.resolve("unconfigured/catatonit"));
        assertThat(
                        TaskInit.missing(
                                new ProcessCommandRunner(),
                                List.of(root.resolve("legacy"), unrelated),
                                "",
                                root,
                                root.resolve("home"),
                                Map.<String, String>of()::get))
                .as("an unconfigured helper must not hide the missing selected helper")
                .isPresent();
        final Path fallback = executable(root.resolve("fallback/catatonit"));
        assertThat(
                        TaskInit.missing(
                                new ProcessCommandRunner(),
                                List.of(root.resolve("legacy")),
                                fallback.getParent().toString(),
                                root,
                                root.resolve("home"),
                                Map.<String, String>of()::get))
                .as("Podman still searches PATH after its configured helpers")
                .isEmpty();
    }

    @Test
    void aFailedProbeStandsAside(@TempDir Path root) {
        // Failure of Porelo's own probe is no evidence that Podman cannot start the task.
        final var runner = new org.fuin.porelo.testing.FakeCommandRunner().missing("/bin/sh");

        assertThat(TaskInit.missing(runner, List.of(), ""))
                .as("an unavailable probe must not invent a missing init")
                .isEmpty();
    }

    private static Path executable(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\nexit 99\n");
        return Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    private static void conf(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
