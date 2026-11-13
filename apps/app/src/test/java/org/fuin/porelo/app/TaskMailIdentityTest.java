package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Identity checks keep mail and transport credentials with their original owner. */
class TaskMailIdentityTest {
    @TempDir Path dir;
    private final FakeCommandRunner runner = new TransportTestRunner();

    private PoreloContext context() {
        final var xdg =
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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private void record(PoreloContext context, String container, String project, String task)
            throws Exception {
        LegacyTaskFixture.record(context, container, project, "a".repeat(64));
        final Path sidecar = context.paths().tasks().taskRecord(container).resolve("sidecar.json");
        Files.writeString(
                sidecar, Files.readString(sidecar).replace("{", "{\"task\":\"" + task + "\","));
    }

    @Test
    void rejectedWorkWithoutATaskNotifiesNobody() throws Exception {
        // A successful rejection must not fail after its pending ref has already been deleted.
        final var context = context();
        final var project =
                ProjectReader.read(
                        new java.io.StringReader(
                                "project:\n  name: web\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\n"),
                        "project.yml");
        assertThat(PersonNote.rejectedWork(context, project, "enroll-host", null)).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retirementKeepsItsOwnerAfterTaskRecordsAreDeletedOrBeforeTheyExist(boolean created)
            throws Exception {
        // Removing ordinary state must not leave the remote account and its credential alive.
        final char[] phrase = "temporary test passphrase".toCharArray();
        final var context = context().asking(prompt -> phrase.clone());
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of(), phrase);
        assertThat(context.openerAsking(false, new PrintWriter(new StringWriter()))).isPresent();
        final Path project = dir.resolve("web.yml");
        Files.writeString(
                project,
                "project:\n  name: web\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\nmail:\n  transports:\n    room: {}\n");
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("web"), project + "\n");
        final Path adapter =
                context.paths()
                        .xdg()
                        .data()
                        .resolve("transports")
                        .resolve(TransportDirectory.PREFIX + "room");
        Files.createDirectories(adapter.getParent());
        Files.writeString(adapter, "#!/bin/sh\n");
        assertThat(adapter.toFile().setExecutable(true)).isTrue();
        runner.answering("describe", "{\"scheme\":\"room\",\"lifecycle\":[\"retire\"]}");
        runner.answering("retire", "{}");
        final String actual = "porelo-web_review";
        final String prefix = TaskSecrets.TRANSPORT_PREFIX + "room/task/";
        try (var lease =
                new TaskReservations(context)
                        .reserve("web", "review", actual, java.util.UUID.randomUUID().toString())) {
            final Path runtime = context.paths().tasks().containerState(actual);
            Files.createDirectories(runtime);
            if (created) {
                record(context, actual, "web", "review");
                org.fuin.porelo.wire.Sidecar.recordId(runtime, "a".repeat(64));
                lease.created("a".repeat(64));
                new TaskState(context).forget(actual);
                try (var files = Files.walk(runtime)) {
                    for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                        Files.delete(file);
                }
            }
            context.vault()
                    .update(
                            context.opener().orElseThrow(),
                            all -> {
                                all.put(
                                        prefix + actual,
                                        org.fuin.porelo.vault.VaultEntry.of("{\"TOKEN\":\"own\"}"));
                                all.put(
                                        prefix + "porelo-web-app-review",
                                        org.fuin.porelo.vault.VaultEntry.of(
                                                "{\"TOKEN\":\"other\"}"));
                                return all;
                            });
            new TaskConversations(context).retire(context, actual, "web");
            final var lifecycle =
                    new TransportLifecycle(
                            context, context.paths().messaging().transportDirectory());
            assertThat(lifecycle.secrets("room", "task/" + actual)).isEmpty();
            assertThat(lifecycle.secrets("room", "task/porelo-web-app-review"))
                    .containsEntry("TOKEN", "other");
        }
    }

    @Test
    void oldAndEncodedRecipientsGetOnlyTheirOwnUnchangedMail() throws Exception {
        // The same logical task in two projects must never share a message or signature.
        final var context = context();
        record(context, "porelo-web_x", "web", "x");
        record(context, "porelo-web-app-x", "web-app", "x");
        final var own = new Mailbox(context.paths().messaging().mailbox("porelo-web_x"));
        final var other = new Mailbox(context.paths().messaging().mailbox("porelo-web-app-x"));
        own.create();
        other.create();
        for (String project : List.of("web", "web-app")) {
            final String actual = project.equals("web") ? "porelo-web_x" : "porelo-web-app-x";
            final Mailbox mailbox = project.equals("web") ? own : other;
            final var identity =
                    ((TaskIdentities.Found) new TaskIdentities(context).lookup("x", project))
                            .identity();
            final Path inbound = Files.createDirectories(dir.resolve(project));
            final String name = project + ".json";
            final String bytes =
                    "{\"role\":\"ROLE_AGENT\",\"metadata\":{\"to\":\"x\"},\"body\":\""
                            + project
                            + "\"}";
            Files.writeString(inbound.resolve(name), bytes);
            Files.writeString(inbound.resolve(name + ".sig"), "test signature");
            TransportConversations.handOut(
                    inbound,
                    List.of(
                            new TransportConversations.Member(
                                    actual, mailbox, project, "room", "", identity.task())),
                    Map.of(),
                    Map.of(),
                    new java.util.LinkedHashMap<>(),
                    new java.util.ArrayList<>());
            assertThat(mailbox.inbound().resolve(name)).hasContent(bytes);
            assertThat(mailbox.inbound().resolve(name + ".sig")).hasContent("test signature");
        }
        assertThat(own.inbound().resolve("web-app.json")).doesNotExist();
        assertThat(other.inbound().resolve("web.json")).doesNotExist();
    }

    @Test
    void aPersonMentionKeepsTheVerifiedLogicalTaskAndNumericSuffix() throws Exception {
        // A mention decoded from the container spelling can wake the wrong task or nobody.
        final var context = context();
        final String actual = "porelo-web_review-123";
        record(context, actual, "web", "review-123");
        final var identity =
                ((TaskIdentities.Found) new TaskIdentities(context).lookup(actual, "web"))
                        .identity();
        final Path message = dir.resolve("person.json");
        Files.writeString(
                message, "{\"sender\":\"@alice\",\"body\":\"Review this\",\"to\":[\"@review\"]}");
        final var words =
                PersonsWords.read(
                        message,
                        List.of(
                                new TransportConversations.Member(
                                        actual,
                                        new Mailbox(dir.resolve("mail")),
                                        "web",
                                        "room",
                                        "",
                                        identity.task())),
                        Map.of(actual, "@review"),
                        Map.of("alice", "@alice"));
        final Map<?, ?> received =
                (Map<?, ?>)
                        org.fuin.porelo.wire.Json.parse(
                                new String(
                                        words.message(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(((Map<?, ?>) received.get("metadata")).get("to"))
                .isEqualTo(List.of("review-123"));
    }

    @Test
    void anEnrollmentBeforeContainerCreationUsesItsReservedLogicalTask() throws Exception {
        // The first room display must address the task before it has a runtime id.
        final char[] phrase = "temporary test passphrase".toCharArray();
        final var context = context().asking(prompt -> phrase.clone());
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of(), phrase);
        assertThat(context.openerAsking(false, new PrintWriter(new StringWriter()))).isPresent();
        final Path adapter =
                context.paths()
                        .xdg()
                        .data()
                        .resolve("transports")
                        .resolve(TransportDirectory.PREFIX + "room");
        Files.createDirectories(adapter.getParent());
        Files.writeString(adapter, "#!/bin/sh\n");
        assertThat(adapter.toFile().setExecutable(true)).isTrue();
        runner.answering(
                "describe", "{\"scheme\":\"room\",\"display\":true,\"lifecycle\":[\"enroll\"]}");
        runner.answering("enroll", "{}");
        try (var lease =
                new TaskReservations(context)
                        .reserve(
                                "web",
                                "review",
                                "porelo-web-review",
                                java.util.UUID.randomUUID().toString())) {
            final var lifecycle =
                    new TransportLifecycle(
                            context, context.paths().messaging().transportDirectory());
            lifecycle.enroll("room", "web", "porelo-web-review", "scout", Map.of(), false);
            assertThat(lifecycle.displays("room", "web"))
                    .containsEntry("porelo-web-review", "Agent Review");
        }
    }

    @Test
    void anUnscopedPassFindsTheEncodedMailboxAndLeavesItsSiblingAlone() throws Exception {
        // A valid encoded endpoint must not lose its queued mail or borrow a sibling's mailbox.
        final var context = context();
        record(context, "porelo-web_x", "web", "x");
        record(context, "porelo-web-app-x", "web-app", "x");
        final var own = new Mailbox(context.paths().messaging().mailbox("porelo-web_x"));
        final var other = new Mailbox(context.paths().messaging().mailbox("porelo-web-app-x"));
        own.create();
        other.create();
        Files.writeString(other.inbound().resolve("sentinel.json"), "{}");
        final Path project = dir.resolve("web.yml");
        Files.writeString(
                project,
                "project:\n  name: web\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\n");
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("web"), project + "\n");
        final var cli = PoreloCli.commandLine(context);
        final var err = new StringWriter();
        cli.setErr(new PrintWriter(err));
        cli.setOut(new PrintWriter(new StringWriter()));
        assertThat(cli.execute("talk", "pass", "porelo-web_x")).as(err.toString()).isZero();
        assertThat(other.inbound().resolve("sentinel.json")).hasContent("{}");
    }

    @Test
    void clearingOneProjectKeepsTheOverlappingProjectsTransportSecret() throws Exception {
        // Prefix deletion erases web-app's credential when web is cleared.
        final char[] phrase = "temporary test passphrase".toCharArray();
        final var context = context().asking(prompt -> phrase.clone());
        Files.createDirectories(context.vault().path().getParent());
        final String prefix = TaskSecrets.TRANSPORT_PREFIX + "room/task/";
        context.vault()
                .write(
                        Map.of(
                                prefix + "porelo-web-app-x",
                                org.fuin.porelo.vault.VaultEntry.of("{\"TOKEN\":\"other\"}"),
                                prefix + "porelo-web_x",
                                org.fuin.porelo.vault.VaultEntry.of("{\"TOKEN\":\"own\"}")),
                        phrase);
        assertThat(context.openerAsking(false, new PrintWriter(new StringWriter()))).isPresent();
        record(context, "porelo-web-app-x", "web-app", "x");
        record(context, "porelo-web_x", "web", "x");
        final var lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        lifecycle.clear("room", "web", Map.of());
        assertThat(lifecycle.secrets("room", "task/porelo-web-app-x"))
                .containsEntry("TOKEN", "other");
        assertThat(lifecycle.secrets("room", "task/porelo-web_x")).isEmpty();
    }

    @Test
    void aWatchFindsEncodedSiblingsByTheirWholeLogicalName() throws Exception {
        // Missing a sibling prevents mail delivery; accepting another project vouches for a
        // stranger.
        final var context = context();
        record(context, "porelo-web_review-123", "web", "review-123");
        runner.answering(
                "podman ps",
                "porelo-web_review-123\tUp 1 minute\t\t\tweb\tguarded\tr\t\t"
                        + "a".repeat(64)
                        + "\treview-123\n");
        final var project =
                ProjectReader.read(
                        new java.io.StringReader(
                                "project:\n  name: web\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\nmail:\n  peers:\n    reviewer: { address: \"room:\", trust: vouched }\n"),
                        "project.yml");
        assertThat(
                        new MessageWatch(context, java.time.Duration.ZERO)
                                .withSiblings(project, "porelo-web-lead")
                                .peers())
                .extracting(org.fuin.porelo.core.project.Mail.Peer::name)
                .contains("review-123");
    }
}
