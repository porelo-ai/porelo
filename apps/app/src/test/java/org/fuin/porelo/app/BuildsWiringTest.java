package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Builds;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuildsWiringTest {

    @TempDir Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"host-builds", "forge.person"})
    void aTaskKeepsTheMissingCredentialAndItsRestartRemedy(String credential) throws Exception {
        // A running task needs a typed restart remedy even after the vault entry has been stored.
        final PoreloContext context = context();
        final Path projectFile = dir.resolve("project.yml");
        Files.writeString(
                projectFile,
                """
                project:
                  name: demo
                  security_class: guarded
                  upstream: https://forge.example/demo.git
                image:
                  from: ubuntu:24.04
                """);
        final var project = ProjectReader.read(projectFile);
        final StringWriter error = new StringWriter();
        new BuildsWiring(
                        context,
                        (name, command, environment, phase) -> {
                            throw new AssertionError("a missing credential must start no helper");
                        })
                .start(
                        project,
                        project.repository("demo"),
                        "porelo-demo-fix",
                        "porelo/fix",
                        new Builds("host-forge", credential, "", Builds.FAILURE),
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(error));

        final Map<String, Object> task =
                new TaskInventory(context, null).tasks().getFirst().asMap();
        assertThat(task.get("buildsCredential"))
                .isEqualTo(
                        Map.of(
                                "for",
                                "builds",
                                "credential",
                                credential,
                                "forge",
                                "host-forge",
                                "storeCommand",
                                List.of("porelo", "host", "forge-token"),
                                "afterStore",
                                "restart"));
        assertThat(task.get("buildProblem"))
                .isEqualTo(error.toString().strip().substring("porelo: ".length()));
        context.vault()
                .write(
                        Map.of(
                                credential,
                                org.fuin.porelo.vault.VaultEntry.of("fixture-forge-token")),
                        "test passphrase".toCharArray());
        assertThat(new TaskInventory(context, null).tasks().getFirst().asMap())
                .as("storing the entry does not start a reader for an already running task")
                .containsEntry("buildsCredential", task.get("buildsCredential"));
    }

    @Test
    void aRestartRetriesTheBuildReaderDeferredForAMissingCredential() throws Exception {
        // A restart remedy must actually restore build feedback after the token is stored.
        final PoreloContext context = context().asking(shown -> "test passphrase".toCharArray());
        final FakeCommandRunner runner = (FakeCommandRunner) context.runner();
        runner.answering(
                "ps",
                "porelo-demo-fix\tExited (0) 1 minute ago\t1788500000\t0\tdemo\tguarded\tdemo\t\t0123456789ab\tfix\n");
        runner.answering("{{.Id}}", "0123456789ab");
        runner.answering("{{.State.Pid}}", "123");
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState("porelo-demo-fix"));
        org.fuin.porelo.wire.Sidecar.recordId(state, "0123456789ab");
        final Path called = dir.resolve("called");
        final Path helper =
                Files.writeString(
                        dir.resolve("recording-porelo"),
                        "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + called + "'\n");
        helper.toFile().setExecutable(true);
        final Path projectFile =
                Files.writeString(
                        dir.resolve("project.yml"),
                        """
                project:
                  name: demo
                  security_class: guarded
                  upstream: https://forge.example/demo.git
                image:
                  from: ubuntu:24.04
                """);
        final var project = ProjectReader.read(projectFile);
        final String previous = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, helper.toString());
        try {
            new BuildsWiring(
                            context,
                            (name, command, environment, phase) -> {
                                throw new AssertionError("the first start must launch no helper");
                            })
                    .start(
                            project,
                            project.repository("demo"),
                            "porelo-demo-fix",
                            "porelo/fix",
                            new Builds("host-forge", "host-builds", "", Builds.ALL),
                            new PrintWriter(new StringWriter()),
                            new PrintWriter(new StringWriter()));
            assertThat(called).as("the missing token starts no reader").doesNotExist();
            new TaskHelpers(List.of()).writeTo(state);
            // What the start records, under the id podman lists for it.
            TaskIdentityFixture.record(
                    context.paths(), "porelo-demo-fix", "demo", "fix", "0123456789ab");
            Files.copy(
                    context.paths().tasks().taskRecord("porelo-demo-fix").resolve("sidecar.json"),
                    state.resolve("sidecar.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            new TaskState(context).save("porelo-demo-fix");
            Files.delete(state.resolve(TaskBuilds.READER_FILE));
            new TaskState(context).restore("porelo-demo-fix");
            new TaskControl(context).resume("porelo-demo-fix");
            assertThat(called)
                    .as("a restart before storing the token still starts no reader")
                    .doesNotExist();
            context.vault()
                    .write(
                            Map.of(
                                    "host-builds",
                                    org.fuin.porelo.vault.VaultEntry.of("fixture-forge-token")),
                            "test passphrase".toCharArray());
            assertThat(context.openerAsking(false, new PrintWriter(System.err))).isPresent();

            new TaskControl(context).resume("porelo-demo-fix");

            for (int attempt = 0; attempt < 200 && !Files.exists(called); attempt++) {
                Thread.sleep(10);
            }
            assertThat(called)
                    .as("restart must invoke the previously deferred build reader")
                    .exists();
            assertThat(Files.readAllLines(called))
                    .as("the original branch and guarded log policy are retained")
                    .containsSequence("--branch", "porelo/fix")
                    .containsSequence("--credential", "host-builds")
                    .containsSequence("--logs", "failure")
                    .contains("--builds-only");
        } finally {
            context.forgetTyped();
            if (previous == null) {
                System.clearProperty(PoreloBinary.NAMED);
            } else {
                System.setProperty(PoreloBinary.NAMED, previous);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aTaskOffersNoHostCredentialRemedyWhenTheEntryIsPresentOrThereIsNoHost(boolean onHost)
            throws Exception {
        // A task must not offer token rotation when its entry exists or no host builds were
        // selected.
        final PoreloContext context = context().asking(shown -> "test passphrase".toCharArray());
        final Path projectFile =
                Files.writeString(
                        dir.resolve("project.yml"),
                        """
                project:
                  name: demo
                  security_class: guarded
                  upstream: https://forge.example/demo.git
                image:
                  from: ubuntu:24.04
                """);
        final var project = ProjectReader.read(projectFile);
        if (onHost) {
            context.vault()
                    .write(
                            Map.of(
                                    "host-builds",
                                    org.fuin.porelo.vault.VaultEntry.of("fixture-forge-token")),
                            "test passphrase".toCharArray());
            assertThat(context.openerAsking(false, new PrintWriter(System.err))).isPresent();
        }
        try {
            new BuildsWiring(
                            context,
                            (name, command, environment, phase) -> {
                                throw new AssertionError(
                                        "the fixture has no installed build reader");
                            })
                    .start(
                            project,
                            project.repository("demo"),
                            "porelo-demo-fix",
                            "porelo/fix",
                            onHost
                                    ? new Builds("host-forge", "host-builds", "", Builds.FAILURE)
                                    : null,
                            new PrintWriter(new StringWriter()),
                            new PrintWriter(new StringWriter()));
            assertThat(new TaskInventory(context, null).tasks().getFirst().asMap())
                    .as("no missing token may be invented for this task")
                    .doesNotContainKey("buildsCredential");
        } finally {
            context.forgetTyped();
        }
    }

    @Test
    void anOlderBuildProblemDoesNotBecomeAMissingCredential() throws Exception {
        // Text from an older record must not cause a client to rotate an unrelated credential.
        final PoreloContext context = context();
        new TaskBuilds(context.paths().tasks())
                .reader(
                        "porelo-demo-fix",
                        "host-forge",
                        "the builds credential is unavailable; run porelo host forge-token");
        assertThat(new TaskInventory(context, null).tasks().getFirst().asMap())
                .doesNotContainKey("buildsCredential");
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final FakeCommandRunner runner = new FakeCommandRunner();
        runner.answering(
                "ps", "porelo-demo-fix\tUp 4 minutes\t1788500000\t0\tdemo\tguarded\tdemo\t\t\n");
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }
}
