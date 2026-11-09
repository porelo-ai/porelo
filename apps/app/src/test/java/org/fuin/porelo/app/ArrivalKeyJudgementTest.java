package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Prevents unsigned, misattributed or unverifiable commits passing without any IO. */
class ArrivalKeyJudgementTest {
    private static final String COMMIT = "a".repeat(40);
    private static final String REF = "refs/heads/porelo/fix";
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    private static final class Checks implements ArrivalKeyJudgement.Checks {
        List<String> persons = List.of("alice");
        boolean revoked;
        String verifiedPerson = "alice";
        ArrivalKeyJudgement.Recorded recorded = new ArrivalKeyJudgement.Recorded(null, null);
        boolean verifiedTask = true;
        boolean checkedPerson;
        boolean checkedTask;

        @Override
        public List<String> persons() {
            return persons;
        }

        @Override
        public boolean revoked() {
            return revoked;
        }

        @Override
        public String person() {
            checkedPerson = true;
            return verifiedPerson;
        }

        @Override
        public ArrivalKeyJudgement.Recorded recorded() {
            return recorded;
        }

        @Override
        public boolean task() {
            checkedTask = true;
            return verifiedTask;
        }
    }

    @Test
    void unsignedCommitBytesCannotReachSignatureVerification() {
        final var answer =
                ArrivalKeyJudgement.signature(
                        "tree a\n\nunsigned\n".getBytes(StandardCharsets.UTF_8), "gpgsig");
        assertThat(answer.refusal())
                .as("an unsigned commit must fail before key attribution")
                .isEqualTo("carries no signature");
        assertThat(answer.signed())
                .as("unsigned data is never represented as a signed payload")
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "-----BEGIN PGP SIGNATURE-----",
                "-----BEGIN CERTIFICATE-----",
                "not a signature"
            })
    void aNonSshSignatureCannotPassThroughTheSshVerifier(String armor) {
        final var answer =
                ArrivalKeyJudgement.signature(
                        ("tree a\ngpgsig " + armor + "\n\nmessage\n")
                                .getBytes(StandardCharsets.UTF_8),
                        "gpgsig");
        assertThat(answer.refusal())
                .as("only an SSH signature is supported")
                .isEqualTo("carries a GPG or X.509 signature, not an SSH one");
    }

    @Test
    void sshArmorWithoutReadableSignatureBytesCannotNameASigner() {
        final var answer =
                ArrivalKeyJudgement.signature(
                        "tree a\ngpgsig -----BEGIN SSH SIGNATURE-----\n junk\n -----END SSH SIGNATURE-----\n\nmessage\n"
                                .getBytes(StandardCharsets.UTF_8),
                        "gpgsig");
        assertThat(answer.refusal())
                .as("malformed signature bytes must not yield a trusted key")
                .isEqualTo("its SSH signature cannot be read");
    }

    @Test
    void aSignatureVerifiedByThePersonsKeyNamesThatPerson() throws Exception {
        final var checks = new Checks();
        final var answer = ArrivalKeyJudgement.judge(COMMIT, REF, FINGERPRINT, checks);
        assertThat(answer.accepted())
                .as("the current person's verified signature can arrive")
                .isTrue();
        assertThat(answer.signer())
                .as("the ledger attributes the actual verified principal")
                .isEqualTo(new HostArrivals.Signer("person", FINGERPRINT, "alice", null, null));
        assertThat(checks.checkedTask)
                .as("a person's key cannot be reinterpreted as a task key")
                .isFalse();
    }

    @Test
    void aRevokedPersonsKeyIsRefusedBeforeCryptoAndCannotFallBackToATask() throws Exception {
        final var checks = new Checks();
        checks.revoked = true;
        final var answer = ArrivalKeyJudgement.judge(COMMIT, REF, FINGERPRINT, checks);
        assertThat(answer.reason())
                .as("revocation must override a matching current signer")
                .isEqualTo("its key " + FINGERPRINT + " is revoked by the KRL");
        assertThat(answer.accepted()).as("a revoked signer cannot arrive").isFalse();
        assertThat(checks.checkedPerson || checks.checkedTask)
                .as("no later check may override the revocation")
                .isFalse();
    }

    @Test
    void aBadSignatureByAKnownPersonsKeyIsRefusedByItsOwnReason() throws Exception {
        final var checks = new Checks();
        checks.verifiedPerson = null;
        final var answer = ArrivalKeyJudgement.judge(COMMIT, REF, FINGERPRINT, checks);
        assertThat(answer.reason())
                .as("a known key is insufficient without a valid signature")
                .isEqualTo("its signature does not verify by the key in force " + FINGERPRINT);
        assertThat(answer.accepted()).as("a tampered signature cannot arrive").isFalse();
        assertThat(checks.checkedTask)
                .as("bad person signatures do not fall back to task certificates")
                .isFalse();
    }

    @Test
    void aSignatureByAKeyThatIsNotThePersonsAndHasNoRecordedCertificateIsRefused()
            throws Exception {
        final var checks = new Checks();
        checks.persons = List.of();
        final var answer = ArrivalKeyJudgement.judge(COMMIT, REF, FINGERPRINT, checks);
        assertThat(answer.reason())
                .as("a valid signature alone does not make a key an authorized signer")
                .isEqualTo(
                        "signed by "
                                + FINGERPRINT
                                + ", which is no signer in force and no task key with a recorded certificate");
        assertThat(answer.accepted()).as("an unknown signer cannot arrive").isFalse();
        assertThat(checks.checkedPerson || checks.checkedTask)
                .as("unknown keys cannot reach a permissive signature checker")
                .isFalse();
    }

    @Test
    void aRecordedTaskCertificateThatFailsItsChecksKeepsTheReason() throws Exception {
        final var checks = new Checks();
        checks.persons = List.of();
        checks.recorded = new ArrivalKeyJudgement.Recorded(null, "its certificate has ended");
        final var answer = ArrivalKeyJudgement.judge(COMMIT, REF, FINGERPRINT, checks);
        assertThat(answer.reason())
                .as("certificate failure must not be folded into unknown signer")
                .isEqualTo(
                        "signed by the task key "
                                + FINGERPRINT
                                + ", whose recorded certificate is not accepted: its certificate has ended");
        assertThat(answer.accepted())
                .as("an ended task certificate cannot authorize the key")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRecordedTaskKeyStillNeedsItsOwnValidCommitSignature(boolean verifies) throws Exception {
        final var checks = new Checks();
        checks.persons = List.of();
        final var signer =
                new HostArrivals.Signer(
                        "task", FINGERPRINT, "task:alpha:fix", "recorded", "SHA256:issuer");
        checks.recorded = new ArrivalKeyJudgement.Recorded(signer, null);
        checks.verifiedTask = verifies;
        final var answer = ArrivalKeyJudgement.judge(COMMIT, REF, FINGERPRINT, checks);
        assertThat(answer.accepted())
                .as("an accepted certificate cannot bless a forged commit signature")
                .isEqualTo(verifies);
        assertThat(answer.reason())
                .as("task signature failure has its own diagnostic")
                .isEqualTo(
                        verifies
                                ? ""
                                : "its signature does not verify by the task key " + FINGERPRINT);
        assertThat(answer.signer())
                .as("task provenance remains available on failure")
                .isEqualTo(signer);
    }
}
