package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pass by hand for the project it was given. Harm guarded: a task whose project its container's
 * name cannot tell - removed, of a project whose name begins another's - had what arrived for it
 * judged by nobody's certifiers, and every message held, although the project was named; and a task
 * named by its own name alone had another project's live mailbox moved instead of its own.
 */
class TalkPassProjectTest {

    @TempDir Path dir;

    /**
     * Runs nothing, except that the message filter, asked on the way in, accepts what it is given
     * as the real one does: it moves the message from its {@code incoming/} to {@code accepted/}.
     */
    private final FakeCommandRunner runner =
            new FakeCommandRunner() {
                @Override
                public CommandResult run(final Command command) {
                    final List<String> arguments = command.arguments();
                    final int mail = arguments.indexOf("--mail");
                    if (arguments.stream()
                                    .anyMatch(each -> each.endsWith(PoreloPaths.MESSAGE_FILTER))
                            && arguments.contains("--blocking")
                            && mail >= 0) {
                        accept(Path.of(arguments.get(mail + 1)));
                    }
                    return super.run(command);
                }
            };

    private static void accept(final Path check) {
        try (Stream<Path> incoming = Files.list(check.resolve("incoming"))) {
            final Path accepted = Files.createDirectories(check.resolve("filter/accepted"));
            for (final Path message : incoming.toList()) {
                Files.move(message, accepted.resolve(message.getFileName()));
            }
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private final StringWriter err = new StringWriter();

    /** A machine whose vault is open for this test alone, with no kernel keyring needed. */
    private PoreloContext context() throws Exception {
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
        final char[] passphrase = "fixture passphrase".toCharArray();
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                        .asking(prompt -> passphrase.clone());
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of(), passphrase);
        assertThat(context.openerAsking(false, new PrintWriter(Writer.nullWriter())))
                .as("the fixture opens its own vault without a kernel keyring")
                .isPresent();
        // This machine's own filter, so one a package installed is never asked instead.
        final Path filter =
                Files.createDirectories(context.paths().xdg().data().resolve("filter"))
                        .resolve(PoreloPaths.MESSAGE_FILTER);
        Files.writeString(filter, "#!/bin/sh\n");
        assertThat(filter.toFile().setExecutable(true)).as("the filter runs").isTrue();
        assertThat(context.paths().messaging().messageFilter())
                .as("the filter asked")
                .isEqualTo(filter);
        return context;
    }

    private void register(final PoreloContext context, final String name, final String more)
            throws IOException {
        final Path home = Files.createDirectories(dir.resolve("projects").resolve(name));
        Files.writeString(
                home.resolve("project.yml"),
                "project:\n  name: \""
                        + name
                        + "\"\n  security_class: \"guarded\"\nimage:\n  from: \"ubuntu:24.04\"\n"
                        + more,
                StandardCharsets.UTF_8);
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve(name),
                home.resolve("project.yml") + "\n",
                StandardCharsets.UTF_8);
    }

    /** A task of the built-in project whose key its person certified, kept as a start keeps it. */
    private void certified(
            final PoreloContext context,
            final CertifiedTaskKeys person,
            final String container,
            final String task) {
        final SigningKey own = person.task(MessageSigners.NO_PROJECT, task);
        assertThat(
                        new TaskSecrets(context)
                                .keepSigning(
                                        container,
                                        new TaskSecrets.Signing(
                                                own.seedBase64(), person.certificate(own).line())))
                .as("the task's key is kept")
                .isEmpty();
    }

    /** A mailbox holding one message that arrived, signed by a key. */
    private Mailbox arrived(
            final PoreloContext context, final String container, final String id, SigningKey by)
            throws Exception {
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        final String json = "{\"messageId\":\"" + id + "\"}";
        Files.writeString(mailbox.inbound().resolve(id + ".json"), json);
        Files.writeString(
                mailbox.inbound().resolve(id + ".json.sig"),
                SshSignature.sign(
                        by, json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
        return mailbox;
    }

    /** What the runtime lists: one live task, labelled with its project. */
    private void running(final String container, final String project) {
        runner.answering(
                "podman ps", container + "\tUp 1 minute\t\t\t" + project + "\tguarded\t\t\tid\n");
    }

    private int pass(final PoreloContext context, final String... arguments) {
        final picocli.CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(new StringWriter()));
        cli.setErr(new PrintWriter(err));
        final String[] all = new String[arguments.length + 2];
        all[0] = "talk";
        all[1] = "pass";
        System.arraycopy(arguments, 0, all, 2, arguments.length);
        return cli.execute(all);
    }

    private static long delivered(final Mailbox mailbox) throws IOException {
        try (Stream<Path> files = Files.list(mailbox.inboxNew())) {
            return files.count();
        }
    }

    @Test
    void aPassGivenTheProjectBelievesWhatThatProjectsCertifierCertified() throws Exception {
        // 'porelo-default-app-fix' fits 'default' and 'default-app' alike, so its name says no
        // project; given 'default', the task believes who certified its own key.
        final PoreloContext context = context();
        register(context, "default-app", "");
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        certified(context, person, "porelo-default-app-fix", "app-fix");
        final SigningKey build = person.task(MessageSigners.NO_PROJECT, "build");
        new TaskCertificateRecords(context).keep("build", "tester", person.certificate(build));
        final Mailbox mailbox = arrived(context, "porelo-default-app-fix", "m-1", build);

        final int code = pass(context, "porelo-default-app-fix", "--project", "default");

        assertThat(code).as(err.toString()).isZero();
        assertThat(delivered(mailbox))
                .as("the message its project's certifier certified is delivered: " + err)
                .isEqualTo(1);
        assertThat(err.toString()).as("nothing held").doesNotContain("held");
    }

    @Test
    void aTaskNamedWithItsProjectIsThatProjectsNeverAnotherOnesOfTheSameTaskName()
            throws Exception {
        // 'fix' alone picks the one live task called so, here 'default-app''s: the project given
        // says it is the removed 'fix' of 'default' whose mailbox is meant.
        final PoreloContext context = context();
        register(context, "default-app", "");
        running("porelo-default-app-fix", "default-app");
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        certified(context, person, "porelo-default-fix", "fix");
        final SigningKey build = person.task(MessageSigners.NO_PROJECT, "build");
        new TaskCertificateRecords(context).keep("build", "tester", person.certificate(build));
        final Mailbox own = arrived(context, "porelo-default-fix", "m-1", build);
        final Mailbox other = arrived(context, "porelo-default-app-fix", "m-2", build);

        final int code = pass(context, "fix", "--project", "default");

        assertThat(code).as(err.toString()).isZero();
        assertThat(delivered(own))
                .as("the given project's task had its message delivered: " + err)
                .isEqualTo(1);
        assertThat(other.inbound().resolve("m-2.json"))
                .as("the other project's live mailbox is left as it was")
                .exists();
    }

    @Test
    void aContainerOfAnotherProjectIsRefusedForTheProjectGiven() throws Exception {
        // 'porelo-default-app-fix' running for 'default-app' is no task of 'default', although
        // its name would make it 'default''s 'app-fix'.
        final PoreloContext context = context();
        register(context, "default-app", "");
        running("porelo-default-app-fix", "default-app");
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        final SigningKey build = person.task(MessageSigners.NO_PROJECT, "build");
        final Mailbox other = arrived(context, "porelo-default-app-fix", "m-2", build);

        final int code = pass(context, "app-fix", "--project", "default");

        assertThat(code).as("refused: " + err).isEqualTo(66);
        assertThat(err.toString())
                .as("says whose task it is not")
                .contains("no task of project default");
        assertThat(other.inbound().resolve("m-2.json"))
                .as("the other project's live mailbox is left as it was")
                .exists();
    }

    @Test
    void aPassGivenTheProjectAsksThatProjectsConversation() throws Exception {
        // Re-derived from 'porelo-web-app-fix', which fits 'web' and 'web-app', the project was
        // none, and what its room held for the task was never fetched.
        final PoreloContext context = context();
        final String room = "mail:\n  transports:\n    room: {}\n";
        register(context, "web", room);
        register(context, "web-app", room);
        final Path adapter =
                Files.createDirectories(dir.resolve("data/porelo/transports"))
                        .resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        assertThat(adapter.toFile().setExecutable(true)).as("the adapter runs").isTrue();
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"display\":true,\"lifecycle\":[\"setup\",\"enroll\",\"join\",\"part\"]}");
        runner.answering(
                " setup ", "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"}}");
        Conversations.setUp(context, GateSupport.byName(context, "web-app"));
        new Mailbox(context.paths().messaging().mailbox("porelo-web-app-fix")).create();

        final int code = pass(context, "porelo-web-app-fix", "--project", "web-app");

        assertThat(code).as(err.toString()).isZero();
        final String into =
                context.paths().xdg().state().resolve("transport/room/inbound/web-app").toString();
        assertThat(runner.invocations())
                .as("the conversation of the project given is asked what arrived: " + err)
                .anySatisfy(
                        command ->
                                assertThat(command.arguments()).contains("poll", "--into", into));
    }

    @Test
    void aQueueLeftInATaskOfDefaultAsksNoTransportAnything() throws Exception {
        // 'default' talks to nobody, whatever was left in its task's mailbox.
        final PoreloContext context = context();
        final Path adapter =
                Files.createDirectories(dir.resolve("data/porelo/transports"))
                        .resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        assertThat(adapter.toFile().setExecutable(true)).as("the adapter runs").isTrue();
        final Mailbox mailbox =
                new Mailbox(context.paths().messaging().mailbox("porelo-default-fix"));
        mailbox.create();
        Files.createDirectories(mailbox.queueActive("room"));

        final int code = pass(context, "porelo-default-fix", "--project", "default");

        assertThat(code).as(err.toString()).isZero();
        assertThat(runner.invocations())
                .as("the transport is not run, not even to describe itself")
                .noneSatisfy(
                        command ->
                                assertThat(command.arguments().toString())
                                        .contains(adapter.toString()));
    }

    @Test
    void aTaskGivenWithItsProjectThatClimbsOutOfItsMailboxIsRefused() throws Exception {
        // 'fix/../porelo-other-secret' would make 'default''s task a path into 'other''s live
        // mailbox, and pass 'default''s conversation into it.
        final PoreloContext context = context();
        register(context, "other", "");
        running("porelo-other-secret", "other");
        new Mailbox(context.paths().messaging().mailbox("porelo-default-fix")).create();
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        final SigningKey build = person.task(MessageSigners.NO_PROJECT, "build");
        final Mailbox other = arrived(context, "porelo-other-secret", "m-2", build);

        final int code = pass(context, "fix/../porelo-other-secret", "--project", "default");

        assertThat(code).as("refused as no task name: " + err).isEqualTo(64);
        assertThat(other.inbound().resolve("m-2.json"))
                .as("the other project's live mailbox is left as it was")
                .exists();
    }

    @Test
    void aPathGivenWithoutAProjectIsNoTaskAndItsDirectoryIsLeftAlone() throws Exception {
        // No live task has the name, so it was taken as it stood - and a path opened any
        // directory that looks like a mailbox.
        final PoreloContext context = context();
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        final SigningKey build = person.task(MessageSigners.NO_PROJECT, "build");
        final Mailbox other = arrived(context, "porelo-other-secret", "m-2", build);

        for (final String given :
                List.of(
                        "porelo-default-fix/../porelo-other-secret",
                        "../mail/porelo-other-secret",
                        other.root().toString())) {
            new Mailbox(context.paths().messaging().mailbox("porelo-default-fix")).create();
            final int code = pass(context, given);

            assertThat(code).as("refused: " + given + ": " + err).isEqualTo(64);
            assertThat(other.inbound().resolve("m-2.json"))
                    .as("the mailbox a path names is left as it was: " + given)
                    .exists();
        }
    }

    /** What a start of a task records under the state directory, which outlives its container. */
    private void recorded(final PoreloContext context, final String container, final String project)
            throws IOException {
        final Path record = Files.createDirectories(context.paths().tasks().taskRecord(container));
        new org.fuin.porelo.wire.Sidecar(
                        org.fuin.porelo.wire.Sidecar.VERSION,
                        project,
                        "guarded",
                        "ruleset.nft",
                        "dnsmasq.conf",
                        "porelo",
                        record.toString())
                .writeTo(record.resolve("sidecar.json"));
    }

    @Test
    void aRemovedTaskOfAnotherProjectIsRefusedForTheProjectGivenByWhatItsStartRecorded()
            throws Exception {
        // Its container gone, 'porelo-default-app-fix' fits 'default' as well, and was opened
        // under it: 'default''s certifiers judged what arrived for 'default-app''s task.
        final PoreloContext context = context();
        register(context, "default-app", "");
        recorded(context, "porelo-default-app-fix", "default-app");
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        final SigningKey build = person.task(MessageSigners.NO_PROJECT, "build");
        new TaskCertificateRecords(context).keep("build", "tester", person.certificate(build));
        final Mailbox other = arrived(context, "porelo-default-app-fix", "m-2", build);

        final int code = pass(context, "app-fix", "--project", "default");

        assertThat(code).as("refused: " + err).isEqualTo(66);
        assertThat(other.inbound().resolve("m-2.json"))
                .as("the other project's mailbox is left as it was")
                .exists();
    }

    @Test
    void aTaskWithoutAProjectWhoseKeyIsCertifiedForAnotherProjectBelievesNobody() throws Exception {
        // Its key names 'default-app''s 'fix': who certified it there may have been locked out of
        // that project, and was believed for 'default' all the same.
        final PoreloContext context = context();
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        final SigningKey own = person.task("default-app", "fix");
        assertThat(
                        new TaskSecrets(context)
                                .keepSigning(
                                        "porelo-default-app-fix",
                                        new TaskSecrets.Signing(
                                                own.seedBase64(), person.certificate(own).line())))
                .as("the task's key is kept")
                .isEmpty();
        final SigningKey build = person.task(MessageSigners.NO_PROJECT, "build");
        new TaskCertificateRecords(context).keep("build", "tester", person.certificate(build));
        final Mailbox mailbox = arrived(context, "porelo-default-app-fix", "m-1", build);

        pass(context, "porelo-default-app-fix", "--project", "default");

        assertThat(delivered(mailbox))
                .as("nothing is believed by a certifier of another project's task: " + err)
                .isZero();
    }

    /** A room transport set up for one project, with what its room holds for the project. */
    private Path room(final PoreloContext context, final String project) throws Exception {
        final Path adapter =
                Files.createDirectories(dir.resolve("data/porelo/transports"))
                        .resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        assertThat(adapter.toFile().setExecutable(true)).as("the adapter runs").isTrue();
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"display\":true,\"lifecycle\":[\"setup\",\"enroll\",\"join\",\"part\"]}");
        runner.answering(
                " setup ", "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"}}");
        Conversations.setUp(context, GateSupport.byName(context, project));
        return Files.createDirectories(
                context.paths().xdg().state().resolve("transport/room/inbound").resolve(project));
    }

    private static void posted(final Path inbound, final String id, final String to)
            throws IOException {
        Files.writeString(
                inbound.resolve(id + ".json"),
                org.fuin.porelo.wire.Json.write(
                        Map.of(
                                "messageId",
                                id,
                                "role",
                                MessageIntake.AGENT,
                                "metadata",
                                Map.of("to", to))));
        Files.writeString(inbound.resolve(id + ".json.sig"), "signature");
    }

    @Test
    void aRemovedTaskOfAProjectNoLongerFollowedIsGivenNoOtherProjectsConversation()
            throws Exception {
        // A removed task must not receive a different project's room through a shared name prefix.
        final PoreloContext context = context();
        final String mail = "mail:\n  transports:\n    room: {}\n";
        register(context, "web", mail);
        final Path inbound = room(context, "web");
        recorded(context, "porelo-web-work", "web");
        recorded(context, "porelo-web-app-fix", "web-app");
        final Mailbox work = new Mailbox(context.paths().messaging().mailbox("porelo-web-work"));
        work.create();
        final Mailbox other =
                new Mailbox(context.paths().messaging().mailbox("porelo-web-app-fix"));
        other.create();
        posted(inbound, "m-1", "work");
        posted(inbound, "m-2", "porelo-web-app-fix");

        final int code = pass(context, "porelo-web-work", "--project", "web");

        assertThat(code).as(err.toString()).isZero();
        assertThat(work.record().resolve(TransportConversations.ARRIVED))
                .as("the room's message reached the project's own task: " + err)
                .exists();
        for (final String id : List.of("m-1", "m-2")) {
            assertThat(other.inbound().resolve(id + ".json"))
                    .as("another project's mailbox is given nothing of this room")
                    .doesNotExist();
        }
        assertThat(Files.readAllLines(work.record().resolve(TransportConversations.ARRIVED)))
                .as("a foreign recipient is not broadcast to the local project")
                .containsExactly("m-1.json");
    }

    @Test
    void aRemovedSiblingWhoseNameFitsTwoProjectsKeepsWhatItsRoomHeldForIt() throws Exception {
        // An ambiguous name must not lose its room's message or reach another project.
        final PoreloContext context = context();
        final String mail = "mail:\n  transports:\n    room: {}\n";
        register(context, "web", mail);
        register(context, "web-app", mail);
        final Path inbound = room(context, "web-app");
        recorded(context, "porelo-web-app-fix", "web-app");
        recorded(context, "porelo-web-app-build", "web-app");
        new Mailbox(context.paths().messaging().mailbox("porelo-web-app-fix")).create();
        final Mailbox build =
                new Mailbox(context.paths().messaging().mailbox("porelo-web-app-build"));
        build.create();
        recorded(context, "porelo-web-build", "web");
        final Mailbox other = new Mailbox(context.paths().messaging().mailbox("porelo-web-build"));
        other.create();
        posted(inbound, "m-1", "build");

        final int code = pass(context, "porelo-web-app-fix", "--project", "web-app");

        assertThat(code).as(err.toString()).isZero();
        assertThat(build.inbound().resolve("m-1.json"))
                .as("the sibling is handed what its room held: " + err)
                .exists();
        assertThat(other.inbound().resolve("m-1.json"))
                .as("the same short name in another project receives nothing")
                .doesNotExist();
        assertThat(
                        new Mailbox(context.paths().messaging().mailbox("porelo-web-app-fix"))
                                .record()
                                .resolve(TransportConversations.ARRIVED))
                .as("the unaddressed sibling receives nothing")
                .doesNotExist();
    }

    @Test
    void aTaskIsReachedOnlyByTheDisplayNameItsRoomActuallyShows() throws Exception {
        // A guessed display name can wake the wrong task when the room assigned another name.
        final PoreloContext context = context();
        register(context, "p", "mail:\n  transports:\n    room: {}\n");
        final Path inbound = room(context, "p");
        runner.answering(" enroll ", "{\"secrets\":{\"TASK\":\"task-token\"}}");
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        lifecycle.enroll("room", "p", "porelo-p-review", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-build", "scout", Map.of(), false);
        recorded(context, "porelo-p-review", "p");
        recorded(context, "porelo-p-build", "p");
        final Mailbox review = new Mailbox(context.paths().messaging().mailbox("porelo-p-review"));
        final Mailbox build = new Mailbox(context.paths().messaging().mailbox("porelo-p-build"));
        review.create();
        build.create();
        posted(inbound, "m-1", "Agent Review");
        posted(inbound, "m-2", "Agent Build");
        posted(inbound, "m-3", "Agent Scout");

        final int code = pass(context, "porelo-p-review", "--project", "p");

        assertThat(code).as(err.toString()).isZero();
        assertThat(review.record().resolve(TransportConversations.ARRIVED))
                .as(
                        "the actual room name reaches the task; its unassigned agent-derived name does not")
                .hasContent("m-1.json\n");
        assertThat(build.record().resolve(TransportConversations.ARRIVED))
                .as("the second task is reached only by its own assigned display")
                .hasContent("m-2.json\n");
    }
}
