package org.fuin.porelo.ecosystem.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.EcosystemRefused;
import org.fuin.porelo.ecosystem.api.File;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.ecosystem.api.InstalledEcosystem;
import org.fuin.porelo.ecosystem.api.Repository;
import org.fuin.porelo.ecosystem.api.Unplaced;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StubEcosystemTest {

    private static final String ANSWERS =
            """
            {"detect": {
               "findings": [{"part": "TOOLS", "key": "java.version", "value": "25", "file": "pom.xml", "line": 14,
                             "origin": "CHECKOUT", "unknown": false, "note": "from the stub"}],
               "unplaced": [{"file": "pom.xml", "line": 40, "note": "a profile"}]},
             "configure": {
               "files": [{"path": ".stub/settings", "content": "http://127.0.0.1:1/corp/", "ownerOnly": true}]}}
            """;

    private static final Finding FINDING =
            new Finding(
                    Finding.Part.TOOLS,
                    "java.version",
                    "25",
                    "pom.xml",
                    14,
                    Finding.Origin.CHECKOUT,
                    false,
                    "from the stub");

    private static final File FILE = new File(".stub/settings", "http://127.0.0.1:1/corp/", true);

    private static final List<Repository> REPOSITORIES =
            List.of(new Repository("corp", "http://127.0.0.1:1/corp/"));

    @TempDir Path directory;

    @Test
    void answersFromItsFileReadAgainOnEveryCall() throws IOException {
        final StubEcosystem stub = new StubEcosystem(answers(ANSWERS));

        assertThat(stub.detect(directory, directory))
                .isEqualTo(
                        new Detection(
                                List.of(FINDING),
                                List.of(new Unplaced("pom.xml", 40, "a profile"))));
        assertThat(stub.configure(REPOSITORIES)).containsExactly(FILE);

        answers("{\"detect\": {\"findings\": [], \"unplaced\": []}}");
        assertThat(stub.detect(directory, directory))
                .as("what the test wrote since: recognized, with nothing to say")
                .isEqualTo(new Detection(List.of(), List.of()));
        assertThat(stub.configure(REPOSITORIES)).as("no files were written for it").isEmpty();
    }

    @Test
    void aFileWithoutADetectIsADirectoryItDoesNotRecognize() throws IOException {
        final StubEcosystem stub = new StubEcosystem(answers("{}"));

        assertRefused(stub, EcosystemRefused.Reason.NOT_RECOGNIZED);
    }

    @Test
    void refusesEveryCallAsItsFileSays() throws IOException {
        final StubEcosystem stub =
                new StubEcosystem(
                        answers(
                                "{\"refuse\": {\"reason\": \"UNREADABLE\", \"detail\": \"pom.xml: no\"}}"));

        assertRefused(stub, EcosystemRefused.Reason.UNREADABLE);
        assertThatThrownBy(() -> stub.configure(REPOSITORIES))
                .isInstanceOfSatisfying(
                        EcosystemRefused.class,
                        refused -> assertThat(refused.detail()).isEqualTo("pom.xml: no"));
    }

    @Test
    void findsItsAnswersBesideItselfUnlessTheEnvironmentNamesThem() {
        assertThat(StubEcosystem.answers(null, "/data/porelo/ecosystems/" + StubEcosystem.NAME))
                .isEqualTo(Path.of("/data/porelo/ecosystems/answers.json"));
        assertThat(StubEcosystem.answers("/elsewhere/a.json", "/anywhere/" + StubEcosystem.NAME))
                .isEqualTo(Path.of("/elsewhere/a.json"));
    }

    @Test
    void aMissingOrBrokenFileIsUnreadable() throws IOException {
        assertRefused(
                new StubEcosystem(directory.resolve("missing.json")),
                EcosystemRefused.Reason.UNREADABLE);
        assertRefused(new StubEcosystem(answers("[]")), EcosystemRefused.Reason.UNREADABLE);
        assertRefused(
                new StubEcosystem(answers("{\"detect\": {\"findings\": 1}}")),
                EcosystemRefused.Reason.UNREADABLE);
    }

    @Test
    void servesItsAnswersOverTheSocketToTheClientPoreloUses() throws IOException {
        answers(ANSWERS);
        final Path executable =
                Files.createDirectories(directory.resolve("bin")).resolve(StubEcosystem.NAME);
        Files.writeString(
                executable,
                "#!/bin/sh\nexport "
                        + StubEcosystem.ANSWERS
                        + "='"
                        + directory.resolve("stub.json")
                        + "'\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' "
                        + StubEcosystem.class.getName()
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));

        try (InstalledEcosystem plugin = new InstalledEcosystem(executable, directory)) {

            assertThat(plugin.ecosystem()).isEqualTo(StubEcosystem.NAME);
            assertThat(plugin.detect(directory, directory).findings()).containsExactly(FINDING);
            assertThat(plugin.configure(REPOSITORIES)).containsExactly(FILE);
        }
    }

    private void assertRefused(final StubEcosystem stub, final EcosystemRefused.Reason reason) {
        assertThatThrownBy(() -> stub.detect(directory, directory))
                .isInstanceOfSatisfying(
                        EcosystemRefused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    private Path answers(final String json) throws IOException {
        return Files.writeString(directory.resolve("stub.json"), json, StandardCharsets.UTF_8);
    }
}
