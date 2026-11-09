package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.fuin.porelo.app.HostArrivals.Signer;
import org.fuin.porelo.gate.SignedArrivals;
import org.fuin.porelo.gate.SignedArrivals.Signed;
import org.fuin.porelo.vault.SshSignature;
import org.jspecify.annotations.Nullable;

/** Pure signature framing and key attribution; git, revocation and crypto checks are adapters. */
final class ArrivalKeyJudgement {
    record Signature(@Nullable Signed signed, @Nullable String key, String refusal) {}

    record Recorded(@Nullable Signer signer, @Nullable String refusal) {}

    interface Checks {
        List<String> persons();

        boolean revoked() throws IOException;

        @Nullable String person() throws IOException;

        Recorded recorded() throws IOException;

        boolean task() throws IOException;
    }

    private ArrivalKeyJudgement() {}

    static Signature signature(byte[] commit, String header) {
        final var signed = SignedArrivals.Signed.of(commit, header);
        if (signed == null) return new Signature(null, null, "carries no signature");
        final String armored = new String(signed.signature(), StandardCharsets.UTF_8);
        if (!armored.startsWith("-----BEGIN SSH SIGNATURE-----"))
            return new Signature(null, null, "carries a GPG or X.509 signature, not an SSH one");
        final byte[] blob = SshSignature.signerOf(armored);
        final String type = blob == null ? null : HostArrivals.typeOf(blob);
        if (blob == null || type == null)
            return new Signature(null, null, "its SSH signature cannot be read");
        return new Signature(signed, type + " " + Base64.getEncoder().encodeToString(blob), "");
    }

    static HostArrivals.Verdict judge(String commit, String ref, String fingerprint, Checks checks)
            throws IOException {
        final List<String> persons = checks.persons();
        if (!persons.isEmpty()) {
            final var signer =
                    new HostArrivals.Signer("person", fingerprint, persons.getFirst(), null, null);
            if (checks.revoked())
                return refused(
                        commit, ref, signer, "its key " + fingerprint + " is revoked by the KRL");
            final String principal = checks.person();
            if (principal != null)
                return new HostArrivals.Verdict(
                        commit,
                        ref,
                        true,
                        new HostArrivals.Signer("person", fingerprint, principal, null, null),
                        "");
            return refused(
                    commit,
                    ref,
                    signer,
                    "its signature does not verify by the key in force " + fingerprint);
        }
        final Recorded recorded = checks.recorded();
        if (recorded.signer() == null) {
            return refused(
                    commit,
                    ref,
                    null,
                    recorded.refusal() == null
                            ? "signed by "
                                    + fingerprint
                                    + ", which is no signer in force and no task key with a recorded certificate"
                            : "signed by the task key "
                                    + fingerprint
                                    + ", whose recorded certificate is not accepted: "
                                    + recorded.refusal());
        }
        if (!checks.task())
            return refused(
                    commit,
                    ref,
                    recorded.signer(),
                    "its signature does not verify by the task key " + fingerprint);
        return new HostArrivals.Verdict(commit, ref, true, recorded.signer(), "");
    }

    private static HostArrivals.Verdict refused(
            String commit, String ref, @Nullable Signer signer, String reason) {
        return new HostArrivals.Verdict(commit, ref, false, signer, HostArrivals.text(reason));
    }
}
