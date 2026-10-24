package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for a transport that keeps a conversation of its own - a room - as Porelo sees it: without
 * knowing which transport it is.
 */
class TransportConversationsTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    @Test
    void aWordToHelpersIsAnsweredOnceAsTheProjectWithoutWakingAnyTask() throws Exception {
        // A helper mention must not wake tasks or use their credentials to answer for the relay.
        answerToHelpers(0);
    }

    @Test
    void aFailedHelperAnswerLeavesNoFilesAndWakesNoTaskAndIsNeverRetried() throws Exception {
        // A failed answer must not wake tasks or keep files that send it again on the next pass.
        answerToHelpers(TransportSend.TEMPORARY);
    }

    private void answerToHelpers(int exit) throws Exception {
        final String line =
                "This is Porelo's technical account; it passes your messages on to the tasks and does not answer itself. Mention a task to reach it.";
        final List<Path> temporary = new java.util.ArrayList<>();
        final List<byte[]> answers = new java.util.ArrayList<>();
        final List<String> signatures = new java.util.ArrayList<>();
        final List<String> shown = new java.util.ArrayList<>();
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"persons\":true,\"shown\":true,\"mention\":true,\"lifecycle\":[\"setup\",\"enroll\",\"join\"]}");
        runner.answering(
                "setup",
                "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1\"]}");
        runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"},\"address\":\"@review\"}");
        runner.answering("join", "{\"login\":{\"user\":\"@alice\"}}");
        runner.failing(" send ", exit, exit == 0 ? "" : "the homeserver is busy");
        final PoreloContext context =
                context(
                                command -> {
                                    final List<String> arguments = command.arguments();
                                    if (arguments.contains("send")) {
                                        try {
                                            final Path message = Path.of(arguments.get(2));
                                            final Path signature = Path.of(arguments.get(3));
                                            final Path shownFile =
                                                    Path.of(
                                                            arguments.get(
                                                                    arguments.indexOf("--shown")
                                                                            + 1));
                                            temporary.addAll(
                                                    List.of(message, signature, shownFile));
                                            answers.add(Files.readAllBytes(message));
                                            signatures.add(Files.readString(signature));
                                            shown.add(Files.readString(shownFile));
                                        } catch (IOException ex) {
                                            throw new java.io.UncheckedIOException(ex);
                                        }
                                    }
                                    return runner.run(command);
                                })
                        .asking(prompt -> PASSPHRASE.clone());
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of(), PASSPHRASE);
        assertThat(
                        context.openerAsking(
                                false, new java.io.PrintWriter(java.io.Writer.nullWriter())))
                .as("the fixture opens its own vault without a kernel keyring")
                .isPresent();
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        lifecycle.setup("room", "p", Map.of());
        lifecycle.enroll("room", "p", "porelo-p-review", Map.of());
        new TaskConversations(context).join(context, project("guarded"), "alice", false);
        assertThat(lifecycle.people("room", "p"))
                .as("the fixture admits the sender")
                .containsEntry("alice", "@alice");
        final var key = HostKey.loadOrCreate(context.paths().messaging().messageKey(), "fixture");
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox build = mailbox("porelo-p-build");
        final Path inbound =
                Files.createDirectories(
                        context.paths().xdg().state().resolve("transport/room/inbound/p"));
        final String name = "arrival--room-person-helper.json";
        Files.writeString(
                inbound.resolve(name),
                "{\"sender\":\"@alice\",\"body\":\"Who are you?\",\"to\":[],\"helpers\":true}");
        final List<TransportConversations.Member> members =
                List.of(
                        new TransportConversations.Member("porelo-p-review", review, "p", "room"),
                        new TransportConversations.Member("porelo-p-build", build, "p", "room"));
        final TransportConversations conversations =
                new TransportConversations(
                        context, context.paths().messaging().transportDirectory());

        final TransportConversations.Outcome outcome = conversations.pass(members);
        conversations.pass(members);

        final List<org.fuin.porelo.core.process.Command> sends =
                runner.invocations().stream()
                        .filter(command -> command.arguments().contains("send"))
                        .toList();
        assertThat(sends).as("the relay answers exactly once and never queues a retry").hasSize(1);
        final var sent = sends.getFirst();
        assertThat(sent.environment())
                .as("only the project's polling credential answers")
                .containsEntry("POLLER", "p-1")
                .doesNotContainKeys("ADMIN", "TASK");
        assertThat(sent.arguments())
                .as("the answer goes to the project's room and mentions its author")
                .containsSubsequence("--to", "!room-p")
                .containsSubsequence("--mention", "@alice");
        assertThat(shown)
                .as("the host supplies the standard line while send runs")
                .containsExactly(line);
        final Map<?, ?> answer =
                (Map<?, ?>)
                        org.fuin.porelo.wire.Json.parse(
                                new String(answers.getFirst(), StandardCharsets.UTF_8));
        assertThat(((Map<?, ?>) answer.get("metadata")).get("from"))
                .as("Porelo speaks for its own technical account")
                .isEqualTo("porelo");
        assertThat(((Map<?, ?>) answer.get("metadata")).get("to"))
                .as("the answer addresses the person's account")
                .isEqualTo("@alice");
        assertThat(
                        TransportSend.shown(
                                new String(answers.getFirst(), StandardCharsets.UTF_8), "porelo"))
                .as("the signed message holds the same standard line")
                .isEqualTo(line);
        assertThat(
                        org.fuin.porelo.vault.SshSignature.verify(
                                answers.getFirst(),
                                signatures.getFirst(),
                                key.keyBlob(),
                                MessageIntake.NAMESPACE))
                .as("the normal host message key signs the exact answer bytes")
                .isTrue();
        assertThat(outcome.handed()).as("no task is handed the helper mention").isEmpty();
        for (final Mailbox mailbox : List.of(review, build)) {
            try (var files = Files.walk(mailbox.root())) {
                assertThat(files.filter(Files::isRegularFile))
                        .as("no task gains a message or queued answer")
                        .isEmpty();
            }
        }
        try (var files = Files.list(inbound)) {
            assertThat(files).as("the person file and all answer files are removed").isEmpty();
        }
        assertThat(temporary)
                .as("message, signature and shown line were supplied")
                .hasSize(3)
                .allSatisfy(
                        file ->
                                assertThat(file)
                                        .as("an answer file is removed after sending")
                                        .doesNotExist());
        assertThat(outcome.failures())
                .as("the hand-out says whether the person was answered")
                .contains(
                        exit == 0
                                ? "answered " + name + ": it named only the room's helpers"
                                : "could not answer "
                                        + name
                                        + ": the transport refused it, exit 75");
    }

    private PoreloContext context() throws IOException {
        return context(runner);
    }

    private PoreloContext context(final org.fuin.porelo.core.process.CommandRunner runner)
            throws IOException {
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
        // An adapter the directory finds: its name after the prefix is the scheme.
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static Project project(String securityClass) {
        return ProjectReader.read(
                new java.io.StringReader(
                        """
                project:
                  name: "p"
                  security_class: "%s"
                image:
                  base_image: "ubuntu:24.04"
                mail:
                  transports:
                    room:
                      server: https://example.org
                  peers:
                    reviewer: { address: "room:", trust: vouched }
                """
                                .formatted(securityClass)),
                "test");
    }

    private Mailbox mailbox(String container) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve(container));
        mailbox.create();
        return mailbox;
    }

    private static void message(Path directory, String name, String to) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve(name),
                "{\"metadata\":{\"to\":\"" + to + "\"},\"body\":\"hi\"}",
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(name + ".sig"), "sig", StandardCharsets.UTF_8);
    }

    @Test
    void whatArrivesGoesToEveryTaskOfThatProjectWhomeverItNamesAndNothingIsKept()
            throws IOException {

        // Every agent reads every message in the room (2026-10-04); metadata.to says whom it is
        // meant for.
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox build = mailbox("porelo-p-build");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", "review");
        message(inbound, "$c.json", "someone-on-another-machine");
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        final int dropped =
                TransportConversations.handOut(
                        inbound,
                        List.of(
                                new TransportConversations.Member(
                                        "porelo-p-review", review, "p", "room"),
                                new TransportConversations.Member(
                                        "porelo-p-build", build, "p", "room")),
                        handed,
                        said);

        assertThat(handed)
                .containsEntry("$a.json", "porelo-p-review, porelo-p-build")
                .containsEntry("$c.json", "porelo-p-review, porelo-p-build");
        assertThat(review.inbound().resolve("$a.json")).exists();
        assertThat(build.inbound().resolve("$a.json.sig")).as("with its signature").exists();
        assertThat(dropped).isZero();
        assertThat(said).isEmpty();
        try (var left = Files.list(inbound)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void aMessageTheAgentTookIsMarkedReadOnceAndOnlyAMessageThatCameThroughTheConversation()
            throws IOException {
        final Mailbox review = mailbox("porelo-p-review");
        Files.writeString(
                review.record().resolve(TransportConversations.ARRIVED), "$a.json\n$b.json\n");
        // Taken by the agent: $a as a maildir reader names it, with flags; $b not yet; x never came
        // that way.
        Files.writeString(review.inboxCur().resolve("$a.json:2,S"), "{}");
        Files.writeString(review.inboxNew().resolve("$b.json"), "{}");
        Files.writeString(review.inboxCur().resolve("x.json"), "{}");

        assertThat(TransportConversations.taken(review)).containsExactly("$a.json");
        Files.writeString(review.record().resolve(TransportConversations.MARKED), "$a.json\n");
        assertThat(TransportConversations.taken(review)).as("marked once").isEmpty();
        assertThat(TransportConversations.reference("$a.json")).isEqualTo("$a");
    }

    @Test
    void aPassPollsOncePerProjectWithTheProjectsSecretsAndMarksWhatWasTaken() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,\"confirms\":\"read\","
                            + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                            + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            final Mailbox review = mailbox("porelo-p-review");

            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();
            // Set up again at the next start: given back what it printed, the account's and the
            // project's.
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-other"))
                    .isNull();
            assertThat(
                            runner.invocations().stream()
                                    .filter(command -> command.describe().contains(" setup "))
                                    .toList()
                                    .getLast()
                                    .environment())
                    .containsEntry("ADMIN", "a-1")
                    .containsEntry("POLLER", "p-1");
            Files.writeString(review.record().resolve(TransportConversations.ARRIVED), "$a.json\n");
            Files.writeString(review.inboxCur().resolve("$a.json"), "{}");
            final TransportConversations.Outcome outcome =
                    new TransportConversations(
                                    context, context.paths().messaging().transportDirectory())
                            .pass(
                                    List.of(
                                            new TransportConversations.Member(
                                                    "porelo-p-review", review, "p", "room")));

            assertThat(outcome.marked()).containsExactly("$a.json");
            final org.fuin.porelo.core.process.Command poll = runner.only(" poll ");
            assertThat(poll.environment())
                    .as("the project's secrets, never the account's")
                    .containsEntry("POLLER", "p-1")
                    .doesNotContainKey("ADMIN");
            assertThat(runner.only(" read ").environment())
                    .containsEntry("TASK", "t-1")
                    .doesNotContainKey("ADMIN");
            assertThat(runner.only("--task porelo-p-review").input())
                    .as("the project's settings, verbatim")
                    .contains("\"server\":\"https://example.org\"");
            // Sending acts as the task, to the conversation setup named.
            assertThat(
                            new TransportConversations(
                                            context,
                                            context.paths().messaging().transportDirectory())
                                    .acting("room", "p", "porelo-p-review"))
                    .isEqualTo(new TransportSend.Acting(Map.of("TASK", "t-1"), "!room-p"));
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aReadTheHomeserverRefusesIsReportedAndNeverRecordedAsMarkedWhileATemporaryOneIsAskedAgain()
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        // 77 is the homeserver refusing - a rejected token, an account in no room that holds the
        // event - and was recorded as marked, so the sender's receipt said its message was read
        // when nobody had confirmed it.
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,\"confirms\":\"read\","
                            + "\"lifecycle\":[\"setup\",\"enroll\"]}");
            runner.answering(
                    "setup",
                    "{\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!r\","
                            + "\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.failing(" read $refused", 77, "M_FORBIDDEN: the token was rejected");
            runner.failing(" read $later", TransportSend.TEMPORARY, "the homeserver is busy");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();
            final Mailbox review = mailbox("porelo-p-review");
            Files.writeString(
                    review.record().resolve(TransportConversations.ARRIVED),
                    "$refused.json\n$later.json\n");
            Files.writeString(review.inboxCur().resolve("$refused.json"), "{}");
            Files.writeString(review.inboxCur().resolve("$later.json"), "{}");
            final TransportConversations conversations =
                    new TransportConversations(
                            context, context.paths().messaging().transportDirectory());
            final List<TransportConversations.Member> members =
                    List.of(
                            new TransportConversations.Member(
                                    "porelo-p-review", review, "p", "room"));

            final TransportConversations.Outcome first = conversations.pass(members);
            final TransportConversations.Outcome second = conversations.pass(members);

            assertThat(first.marked()).as("refused is not done").isEmpty();
            assertThat(second.marked()).isEmpty();
            assertThat(review.record().resolve(TransportConversations.MARKED)).doesNotExist();
            assertThat(first.failures())
                    .singleElement()
                    .asString()
                    .contains("refused")
                    .contains("exit 77")
                    .contains("M_FORBIDDEN");
            assertThat(runner.lines().stream().filter(line -> line.contains(" read $refused")))
                    .as("a refusal is final, asking again every pass changes nothing")
                    .hasSize(1);
            assertThat(runner.lines().stream().filter(line -> line.contains(" read $later")))
                    .as("only a temporary failure is asked again")
                    .hasSize(2);
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aTransportThatTakesTheMachinesNameIsGivenItAndOneThatDoesNotIsNot() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"],"
                            + "\"takes\":[\"machine\"]}");
            runner.answering(
                    "setup",
                    "{\"secrets\":{},\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");

            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();

            // Two machines on one homeserver run tasks of the same name; the machine's name keeps
            // them apart.
            assertThat(runner.only(" setup ").arguments())
                    .containsSequence("--machine", DeployKeys.hostName());
            assertThat(runner.only(" enroll ").arguments())
                    .containsSequence("--machine", DeployKeys.hostName());
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aMachineNotLetIntoASharedConversationKeepsItsAccountAndStartsNoTaskUntilItIs()
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"],"
                            + "\"takes\":[\"machine\"]}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"MACHINE\":\"m-1\"},\"machine\":\"host-b\","
                            + "\"admitted\":false,\"address\":\"@porelo.host-b:example.org\",\"room\":\"#p:example.org\"}");

            final String refused =
                    new TaskConversations(context).enroll(project("guarded"), "porelo-p-review");

            assertThat(refused)
                    .as("whom to let in, and where")
                    .contains("@porelo.host-b:example.org")
                    .contains("#p:example.org");
            assertThat(runner.lines())
                    .as("no task is enrolled while the machine is not in")
                    .noneMatch(each -> each.contains(" enroll "));
            assertThat(
                            new TransportLifecycle(
                                            context,
                                            context.paths().messaging().transportDirectory())
                                    .secrets("room", "account"))
                    .as("the account it registered is kept, not lost with a refusal")
                    .containsEntry("MACHINE", "m-1");
            assertThat(Conversations.row(context, project("guarded")))
                    .containsEntry("machine", "host-b")
                    .containsEntry("admitted", false)
                    .containsEntry("address", "@porelo.host-b:example.org")
                    .containsEntry("room", "#p:example.org")
                    .containsEntry("ready", false);
        } finally {
            keyring.forget();
        }
    }

    @Test
    void whatATaskSentIsAskedAboutByTheRecipientsAddressAndRecordedReadOnce() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,\"confirms\":\"read\","
                            + "\"lifecycle\":[\"setup\",\"enroll\"]}");
            runner.answering(
                    "setup",
                    "{\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!r\","
                            + "\"reaches\":[\"127.0.0.1\"]}");
            runner.answering(
                    "--task porelo-p-write",
                    "{\"secrets\":{\"TASK\":\"w-1\"},\"address\":\"@write\"}");
            runner.answering(
                    "--task porelo-p-review",
                    "{\"secrets\":{\"TASK\":\"r-1\"},\"address\":\"@review\"}");
            runner.answering(" receipt ", "{\"state\":\"read\",\"at\":\"2026-09-30T08:00:00Z\"}");
            final TaskConversations tasks = new TaskConversations(context);
            assertThat(tasks.enroll(project("guarded"), "porelo-p-write")).isNull();
            assertThat(tasks.enroll(project("guarded"), "porelo-p-review")).isNull();
            final Mailbox write = mailbox("porelo-p-write");
            Files.writeString(
                    write.sent().resolve("m-1.json"),
                    "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"review\"}}");
            Files.writeString(
                    write.sent().resolve("m-1.json.receipt.json"), "{\"reference\":\"$e1\"}");
            final TransportConversations conversations =
                    new TransportConversations(
                            context, context.paths().messaging().transportDirectory());
            final List<TransportConversations.Member> members =
                    List.of(
                            new TransportConversations.Member(
                                    "porelo-p-write", write, "p", "room"));

            conversations.pass(members);
            conversations.pass(members);

            final org.fuin.porelo.core.process.Command asked = runner.only(" receipt ");
            assertThat(asked.arguments()).endsWith("receipt", "$e1", "--by", "@review");
            assertThat(asked.environment()).as("as the sender").containsEntry("TASK", "w-1");
            assertThat(Files.readString(write.record().resolve(MessageRecord.FILE)))
                    .contains("\"event\":\"read\"")
                    .contains("\"peer\":\"review\"");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void tasksOfOneProjectEnrolledAtOnceAreEachNamedInItsConversation() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        // Two starts of one project's tasks at once each wrote the addresses as they had read them,
        // and one task's address was lost: its own words in the room then came back to it.
        final int tasks = 8;
        final java.util.concurrent.CyclicBarrier together =
                new java.util.concurrent.CyclicBarrier(tasks);
        final org.fuin.porelo.core.process.CommandRunner enrolling =
                command -> {
                    final List<String> arguments = command.arguments();
                    if (arguments.contains("describe")) {
                        return new org.fuin.porelo.core.process.CommandResult(
                                command,
                                0,
                                "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"]}",
                                "");
                    }
                    if (!arguments.contains("enroll")) {
                        return new org.fuin.porelo.core.process.CommandResult(command, 0, "", "");
                    }
                    try {
                        // Every enroll answers at the same moment, as the transport answers starts
                        // that overlap.
                        together.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (final Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                    final String task = arguments.get(arguments.indexOf("--task") + 1);
                    return new org.fuin.porelo.core.process.CommandResult(
                            command, 0, "{\"address\":\"@" + task + "\"}", "");
                };
        final PoreloContext context = context(enrolling);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        // Read once and held, as the daemon holds it, so the starts meet at the transport and not
        // at the vault's key derivation.
        VaultCache.hold();
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            final TransportLifecycle lifecycle =
                    new TransportLifecycle(
                            context, context.paths().messaging().transportDirectory());
            final List<String> containers =
                    java.util.stream.IntStream.range(0, tasks)
                            .mapToObj(n -> "porelo-p-t" + n)
                            .toList();
            final List<Throwable> failed =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            final List<Thread> starts =
                    containers.stream()
                            .map(
                                    container ->
                                            Thread.ofPlatform()
                                                    .start(
                                                            () -> {
                                                                try {
                                                                    lifecycle.enroll(
                                                                            "room", "p", container,
                                                                            Map.of());
                                                                } catch (final Exception ex) {
                                                                    failed.add(ex);
                                                                }
                                                            }))
                            .toList();
            for (final Thread start : starts) {
                start.join(java.time.Duration.ofSeconds(30));
            }

            assertThat(failed).as("every enroll ran").isEmpty();
            assertThat(lifecycle.addresses("room", "p"))
                    .as("each task's address, as its enroll said it")
                    .containsOnlyKeys(containers);
        } finally {
            VaultCache.stop();
            keyring.forget();
        }
    }

    @Test
    void anAddressIsATasksShortOrFullNameOrAPersons() {
        final Map<String, String> tasks = Map.of("porelo-p-review", "@review");
        final Map<String, String> people = Map.of("alice", "@alice");
        assertThat(TransportConversations.addressOf("review", "p", tasks, people))
                .isEqualTo("@review");
        assertThat(TransportConversations.addressOf("porelo-p-review", "p", tasks, people))
                .isEqualTo("@review");
        assertThat(TransportConversations.addressOf("alice", "p", tasks, people))
                .isEqualTo("@alice");
        assertThat(TransportConversations.addressOf("nobody", "p", tasks, people)).isNull();
    }

    @Test
    void anOfflineProjectsConversationMustNotReachBeyondLoopback() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "setup",
                    "{\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!r\","
                            + "\"reaches\":[\"matrix.example.org\"]}");

            assertThat(new TaskConversations(context).enroll(project("offline"), "porelo-p-review"))
                    .contains("an offline project's messages never leave this machine")
                    .contains("matrix.example.org");
            assertThat(runner.lines())
                    .as("never enrolled")
                    .noneMatch(line -> line.contains(" enroll "));
            assertThat(runner.only(" setup ").arguments())
                    .as("told before it reaches anything")
                    .endsWith("--loopback-only");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void loopbackIsLoopbackWithOrWithoutAPort() {
        assertThat(TaskConversations.loopback("127.0.0.1")).isTrue();
        assertThat(TaskConversations.loopback("127.0.0.1:18008")).isTrue();
        assertThat(TaskConversations.loopback("[::1]:18008")).isTrue();
        assertThat(TaskConversations.loopback("localhost")).isTrue();
        assertThat(TaskConversations.loopback("matrix.example.org")).isFalse();
        assertThat(TaskConversations.loopback("127.0.0.1.example.org")).isFalse();
    }

    @Test
    void aTaskWhoseAccountCannotBeReadHereIsNotSentAsNobody() throws Exception {

        // Its conversation is there, its account is not readable - a locked vault, or a daemon that
        // sees another one: send ran without it, and the message came back held with
        // "PORELO_MATRIX_HOMESERVER is not set".
        final PoreloContext context = context();
        final Path state =
                Files.createDirectories(context.paths().xdg().state().resolve("transport/room"));
        Files.writeString(
                state.resolve("p.json"), "{\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");

        final TransportConversations conversations =
                new TransportConversations(
                        context, context.paths().messaging().transportDirectory());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> conversations.acting("room", "p", "porelo-p-review"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no account");
        // A project with no conversation in it asks for nothing beyond the message, as before.
        assertThat(conversations.acting("room", "other", "porelo-other-review")).isNull();
    }

    @Test
    void clearingAProjectRunsTheTransportsClearAndForgetsWhatPoreloKeptForItThenTheAccountsToo()
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        // With them kept, a task started again after the transport had cleared ran no setup and
        // stayed outside a conversation that was gone (measured by porelo-message-matrix,
        // 2026-10-02).
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"clear\"]}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                            + "\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.answering(" clear", "{\"removed\":[\"the room\"],\"kept\":[]}");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();
            final TransportLifecycle lifecycle =
                    new TransportLifecycle(
                            context, context.paths().messaging().transportDirectory());

            final List<String> removed = lifecycle.clear("room", "p", Map.of());

            assertThat(runner.lines()).anyMatch(line -> line.contains(" clear --project p"));
            assertThat(removed).contains("the room");
            assertThat(lifecycle.secrets("room", "project/p")).isEmpty();
            assertThat(lifecycle.secrets("room", "task/porelo-p-review")).isEmpty();
            assertThat(lifecycle.conversation("room", "p"))
                    .as("set up anew at the next start")
                    .isNull();
            assertThat(lifecycle.secrets("room", "account"))
                    .as("the account outlives one project")
                    .isNotEmpty();

            lifecycle.clear("room", null, Map.of());

            assertThat(runner.lines()).anyMatch(line -> line.endsWith(" clear"));
            assertThat(lifecycle.secrets("room", "account")).isEmpty();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aWaitingPollAsksForPersonsAndHoldsTheServerOpenAndAPassThenLeavesFetchingToIt()
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,\"persons\":true,\"waits\":true,"
                            + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                            + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            final Mailbox review = mailbox("porelo-p-review");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();
            final List<TransportConversations.Member> group =
                    List.of(
                            new TransportConversations.Member(
                                    "porelo-p-review", review, "p", "room"));
            final TransportConversations conversations =
                    new TransportConversations(
                            context, context.paths().messaging().transportDirectory());

            conversations.waitOnce(group);

            assertThat(runner.only(" poll ").arguments())
                    .contains("--persons")
                    .containsSubsequence("--wait", "30");
            TransportConversations.WAITING.add(TransportConversations.key("room", "p"));
            try {
                conversations.pass(group);
                assertThat(
                                runner.invocations().stream()
                                        .filter(command -> command.describe().contains(" poll "))
                                        .count())
                        .as("no second poll beside the waiting one")
                        .isEqualTo(1);
            } finally {
                TransportConversations.WAITING.remove(TransportConversations.key("room", "p"));
            }
        } finally {
            keyring.forget();
        }
    }

    @Test
    void whatAPollSaysOnItsErrorIsSaidEvenWhenItSucceeds() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,"
                            + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                            + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.answering(" poll ", "", "skipped 1 encrypted message from @alice:localhost");
            final Mailbox review = mailbox("porelo-p-review");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();

            final TransportConversations.Outcome outcome =
                    new TransportConversations(
                                    context, context.paths().messaging().transportDirectory())
                            .pass(
                                    List.of(
                                            new TransportConversations.Member(
                                                    "porelo-p-review", review, "p", "room")));

            assertThat(outcome.failures())
                    .anySatisfy(said -> assertThat(said).contains("encrypted message"));
        } finally {
            keyring.forget();
        }
    }
}
