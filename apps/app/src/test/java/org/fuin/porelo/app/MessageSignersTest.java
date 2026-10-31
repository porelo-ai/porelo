package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshSignature;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MessageSigners}: a message is believed from a task key whose certificate one of
 * the receiving project's certifiers issued, valid when it arrives here, and from nothing else.
 */
class MessageSignersTest {

    @TempDir Path dir;

    private Mailbox mailbox() throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve("porelo-p-review"));
        mailbox.create();
        return mailbox;
    }

    private static void arrive(final Mailbox mailbox, final SigningKey key, final String id)
            throws IOException {
        arrive(mailbox, key, id, "{\"messageId\":\"" + id + "\"}");
    }

    private static void arrive(
            final Mailbox mailbox, final SigningKey key, final String id, final String json)
            throws IOException {
        Files.writeString(mailbox.inbound().resolve(id + ".json"), json);
        Files.writeString(
                mailbox.inbound().resolve(id + ".json.sig"),
                SshSignature.sign(
                        key, json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
    }

    @Test
    void aMessageByATaskKeyAProjectCertifierCertifiedIsDeliveredAsThatTask() throws IOException {
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir);
        final SigningKey build = keys.task("p", "build");
        final Mailbox mailbox = mailbox();
        arrive(mailbox, build, "m-1");

        final MessageDelivery.Outcome outcome =
                new MessageDelivery().deliver(mailbox, keys.signers("p", keys.task("p", "review")));

        assertThat(outcome.held()).isEmpty();
        assertThat(outcome.delivered())
                .singleElement()
                .satisfies(delivered -> assertThat(delivered.peer()).isEqualTo("build"));
    }

    @Test
    void aMessageByATaskKeyAnotherKeyCertifiedIsHeldNamingTheKey() throws IOException {
        // Its certificate is kept here, as any task's on this machine is; who issued it is what
        // decides.
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir);
        final CertifiedTaskKeys stranger = new CertifiedTaskKeys(dir);
        final SigningKey intruder = stranger.task("p", "build");
        final Mailbox mailbox = mailbox();
        arrive(mailbox, intruder, "m-1");

        final MessageDelivery.Outcome outcome =
                new MessageDelivery()
                        .deliver(mailbox, keys.signers("p", keys.task("p", "review"), stranger));

        assertThat(outcome.delivered()).isEmpty();
        assertThat(outcome.held())
                .singleElement()
                .satisfies(
                        held ->
                                assertThat(held.reason())
                                        .contains(
                                                PersonCard.fingerprint(
                                                        PersonCard.keyLine(intruder)))
                                        .contains("no certifier of p"));
        assertThat(mailbox.hold().resolve("m-1.json")).exists();
    }

    @Test
    void aMessageByAKeyWhoseCertificateIsKeptNowhereIsHeldNamingTheKey() throws IOException {
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir);
        final SigningKey uncertified = SigningKey.generate("uncertified");
        final Mailbox mailbox = mailbox();
        arrive(mailbox, uncertified, "m-1");

        final MessageDelivery.Outcome outcome =
                new MessageDelivery().deliver(mailbox, keys.signers("p", keys.task("p", "review")));

        assertThat(outcome.delivered()).isEmpty();
        assertThat(outcome.held())
                .singleElement()
                .satisfies(
                        held ->
                                assertThat(held.reason())
                                        .contains(
                                                PersonCard.fingerprint(
                                                        PersonCard.keyLine(uncertified)))
                                        .contains("no certificate is kept"));
    }

    @Test
    void aMessageArrivingAfterItsCertificateEndedIsHeldWhateverItSays() throws IOException {
        // The certificate held when the message was written; what counts is when it arrives here,
        // by this machine's clock.
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir);
        final SigningKey build = keys.task("p", "build", "-1d:+1d");
        final Mailbox mailbox = mailbox();
        arrive(mailbox, build, "m-1");
        final Instant later = keys.certificate(build).validBefore().plus(Duration.ofMinutes(1));

        final MessageDelivery.Outcome outcome =
                new MessageDelivery(Clock.fixed(later, ZoneOffset.UTC))
                        .deliver(mailbox, keys.signers("p", keys.task("p", "review")));

        assertThat(outcome.delivered()).isEmpty();
        assertThat(outcome.held())
                .singleElement()
                .satisfies(
                        held ->
                                assertThat(held.reason())
                                        .contains(PersonCard.fingerprint(PersonCard.keyLine(build)))
                                        .contains(keys.certificate(build).validBefore().toString())
                                        .contains("it arrived at " + later));
    }

    @Test
    void aTaskOfAnotherProjectIsItsWholePrincipalNeverAPeerNamedLikeIt() throws IOException {
        // Named 'reviewer', a task of another project would otherwise pass as the peer this project
        // lists by that name, and with that peer's trust.
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir);
        final SigningKey other = keys.task("other", "reviewer");
        final Mailbox mailbox = mailbox();
        arrive(mailbox, other, "m-1");

        final MessageDelivery.Outcome outcome =
                new MessageDelivery().deliver(mailbox, keys.signers("p", keys.task("p", "review")));

        assertThat(outcome.delivered())
                .singleElement()
                .satisfies(
                        delivered -> assertThat(delivered.peer()).isEqualTo("task:other:reviewer"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"task:p:*", "task::build", "task:p:people", "task:p:task:other:build"})
    void aCertificateForNoOneTaskIsHeldWhoeverIssuedIt(final String principal) throws IOException {
        // A pattern, a missing project, a reserved peer or a nested principal would pass one key
        // off as another sender: as the room's people, say, or as a task of another project.
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir);
        final SigningKey odd = keys.certifiedFor(principal);
        final Mailbox mailbox = mailbox();
        arrive(mailbox, odd, "m-1");

        final MessageDelivery.Outcome outcome =
                new MessageDelivery().deliver(mailbox, keys.signers("p", keys.task("p", "review")));

        assertThat(outcome.delivered()).as("delivered as " + principal).isEmpty();
        assertThat(outcome.held())
                .singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("certified for no one task"));
    }

    @Test
    void aMessageNamingAnotherSenderThanItsKeyIsHeld() throws IOException {
        // The agent reads the sender from the message's metadata, which the sending task wrote.
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir);
        final SigningKey build = keys.task("p", "build");
        final Mailbox mailbox = mailbox();
        arrive(
                mailbox,
                build,
                "m-1",
                "{\"messageId\":\"m-1\",\"role\":\"ROLE_AGENT\","
                        + "\"metadata\":{\"from\":\"alice\",\"to\":\"review\"}}");
        arrive(
                mailbox,
                build,
                "m-2",
                "{\"messageId\":\"m-2\",\"role\":\"ROLE_AGENT\","
                        + "\"metadata\":{\"from\":\"build\",\"to\":\"review\"}}");

        final MessageDelivery.Outcome outcome =
                new MessageDelivery().deliver(mailbox, keys.signers("p", keys.task("p", "review")));

        assertThat(outcome.held())
                .as("one that says another task or a person wrote it")
                .singleElement()
                .satisfies(
                        held -> {
                            assertThat(held.message()).isEqualTo("m-1.json");
                            assertThat(held.reason()).contains("alice").contains("build");
                        });
        assertThat(mailbox.inboxNew().resolve("m-1.json")).doesNotExist();
        assertThat(outcome.delivered())
                .as("one that names its own sender")
                .singleElement()
                .satisfies(delivered -> assertThat(delivered.message()).isEqualTo("m-2.json"));
    }

    @Test
    void aTaskOfANamedProjectWhoseFileCannotBeReadBelievesNobodyNotWhoStartedIt() throws Exception {
        // Its project's certifiers are unknown; who certified its own key may since have been
        // locked out of the project.
        Assumptions.assumeTrue(KernelKeyring.available(), "no kernel keyring here");
        final PoreloContext context = context();
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        final SigningKey own = person.task("acme", "review");
        final SigningKey sibling = person.task("acme", "build");
        new TaskCertificateRecords(context).keep("build", "tester", person.certificate(sibling));
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.<String, VaultEntry>of(), passphrase);
            keyring.store(passphrase);
            new TaskSecrets(context)
                    .keepSigning(
                            "porelo-acme-review",
                            new TaskSecrets.Signing(
                                    own.seedBase64(), person.certificate(own).line()));

            final MessageSigners.Traced traced =
                    new MessageWatch(context, Duration.ZERO)
                            .keysOf("porelo-acme-review")
                            .trace(sibling.keyBlob(), Instant.now());

            assertThat(traced.believed()).as("believed as " + traced.peer()).isFalse();
            assertThat(traced.waits()).as("held, not waiting").isFalse();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aTaskWithoutAProjectLeavesWhatArrivesWhereItIsWhileTheVaultIsLocked() throws Exception {
        // Its certifier is read from the vault; held now, the message would never be delivered.
        final PoreloContext context = context();
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        final SigningKey sibling = person.task(MessageSigners.NO_PROJECT, "build");
        new TaskCertificateRecords(context).keep("build", "tester", person.certificate(sibling));
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(Map.<String, VaultEntry>of(), "correct horse battery staple".toCharArray());
        assertThat(context.opener()).as("the vault is locked").isEmpty();
        final Mailbox mailbox = mailbox();
        arrive(mailbox, sibling, "m-1");

        final MessageDelivery.Outcome outcome =
                new MessageDelivery()
                        .deliver(
                                mailbox, MessageSigners.of(context, "porelo-default-review", null));

        assertThat(outcome.held()).as("nothing held").isEmpty();
        assertThat(outcome.delivered()).isEmpty();
        assertThat(mailbox.inbound().resolve("m-1.json")).as("it waits where it arrived").exists();
        assertThat(mailbox.inbound().resolve("m-1.json.sig")).exists();
    }

    @Test
    void aTaskWithoutAProjectSignsWithItsKeyAndBelievesWhoCertifiedIt() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "no kernel keyring here");
        final PoreloContext context = context();
        final CertifiedTaskKeys person = new CertifiedTaskKeys(dir);
        final CertifiedTaskKeys other = new CertifiedTaskKeys(dir);
        final SigningKey own = person.task(MessageSigners.NO_PROJECT, "review");
        final SigningKey sibling = person.task(MessageSigners.NO_PROJECT, "build");
        final SigningKey foreign = other.task(MessageSigners.NO_PROJECT, "build");
        final TaskCertificateRecords records = new TaskCertificateRecords(context);
        records.keep("build", "tester", person.certificate(sibling));
        records.keep("build", "other", other.certificate(foreign));
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.<String, VaultEntry>of(), passphrase);
            keyring.store(passphrase);
            new TaskSecrets(context)
                    .keepSigning(
                            "porelo-default-review",
                            new TaskSecrets.Signing(
                                    own.seedBase64(), person.certificate(own).line()));

            final MessageSigners signers =
                    MessageSigners.of(context, "porelo-default-review", null);

            assertThat(signers.own().keyBlob())
                    .as("signs with its own key, read from the vault")
                    .isEqualTo(own.keyBlob());
            assertThat(signers.trace(sibling.keyBlob(), Instant.now()).peer())
                    .as("a task its own person certified")
                    .isEqualTo("build");
            assertThat(signers.trace(foreign.keyBlob(), Instant.now()).refusal())
                    .as("a task another person certified")
                    .contains("no certifier of default");
        } finally {
            keyring.forget();
        }
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
