package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's row in a listing carries its conversation: the button that opens its room reads it. A
 * project the host carried and the person had not taken yet had none, so the room could not be
 * opened before a first task (2026-11-14).
 */
class ProjectRowConversationTest {

    @TempDir private Path dir;

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
                        dir);
        return new PoreloContext(
                new TransportTestRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    /** Writes the host's file of a project, as a host carrying it for this person does. */
    private Path hostFiles(
            final @org.jspecify.annotations.Nullable String homeserver, final String room)
            throws IOException {
        final Path hostFiles = Files.createDirectories(dir.resolve("host-files"));
        final Map<String, @org.jspecify.annotations.Nullable Object> file =
                new java.util.LinkedHashMap<>();
        file.put("project", "demo");
        file.put("revision", "the-organizations");
        file.put("address", "git@forge.example.org:acme/demo.git");
        file.put("fetch_from", null);
        file.put("signing_keys", List.of());
        file.put("homeserver", homeserver);
        file.put("room", room);
        Files.writeString(hostFiles.resolve("demo.json"), Json.write(file));
        return hostFiles;
    }

    @Test
    void aCarriedProjectNotTakenHereListsTheHostsRoomNotReadyYet() throws IOException {
        final Path hostFiles = hostFiles("unix:/run/porelo-matrix/client.sock", "#demo:host");
        final ProjectInventory.Summary carried =
                new ProjectInventory.Summary("demo", null, null, null, 0, 0, 0).asCarried();

        final Map<String, Object> messages = Conversations.ofProject(context(), carried, hostFiles);

        assertThat(messages).isNotNull();
        assertThat(messages)
                .containsEntry("transport", "matrix")
                .containsEntry("conversation", "#demo:host")
                .containsEntry("room", "#demo:host")
                .containsEntry("ready", false)
                .containsEntry("admitted", false);
        assertThat((String) messages.get("detail")).contains("set up");
    }

    @Test
    void aCarriedProjectWhoseHostNamesNoHomeserverHasNoConversation() throws IOException {
        final Path hostFiles = hostFiles(null, "#demo:host");
        final ProjectInventory.Summary carried =
                new ProjectInventory.Summary("demo", null, null, null, 0, 0, 0).asCarried();

        assertThat(Conversations.ofProject(context(), carried, hostFiles)).isNull();
    }

    @Test
    void aFileThatDoesNotReadSaysSoInsteadOfDroppingTheConversation() throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, "project: [not a project\n");
        final ProjectInventory.Summary broken =
                new ProjectInventory.Summary("demo", null, file.toString(), null, 0, 0, 0);

        final Map<String, Object> messages =
                Conversations.ofProject(context(), broken, dir.resolve("no-host"));

        assertThat(messages).isNotNull();
        assertThat(messages).containsEntry("ready", false);
        assertThat((String) messages.get("detail")).contains("does not read");
    }

    @Test
    void aRefusedValueInTheFileNeverReachesTheDetail() throws IOException {
        // A project file's refusal quotes the value it refused, here a password in an address.
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                "project: {name: demo, security_class: online, central:"
                        + " \"https://forge.example.org/demo.git\"}\n"
                        + "image: {from: \"ubuntu:24.04\"}\n"
                        + "builds: {forge: forge, credential: builds, api:"
                        + " \"http://u:example-password@forge.example.org\"}\n");
        final ProjectInventory.Summary refused =
                new ProjectInventory.Summary("demo", null, file.toString(), null, 0, 0, 0);

        final Map<String, Object> messages =
                Conversations.ofProject(context(), refused, dir.resolve("no-host"));

        assertThat(messages).isNotNull();
        assertThat(messages.toString()).doesNotContain("example-password");
    }

    @Test
    void aFileWithoutAConversationStillHasNone() throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                "project:\n  name: \"demo\"\n  security_class: \"guarded\"\nimage:\n  from:"
                        + " \"ubuntu:24.04\"\n");
        final ProjectInventory.Summary plain =
                new ProjectInventory.Summary("demo", null, file.toString(), null, 0, 0, 0);

        assertThat(Conversations.ofProject(context(), plain, dir.resolve("no-host"))).isNull();
    }
}
