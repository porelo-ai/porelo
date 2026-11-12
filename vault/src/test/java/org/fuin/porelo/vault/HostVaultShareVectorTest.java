package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The written rule for a host vault's opening share, pinned by the test vector {@code
 * doc/credentials.md} states, so another client that derives the same 32 bytes is checked without
 * an agent.
 *
 * <p>The key exists for this vector alone: an Ed25519 seed of the bytes 0x00 to 0x1f, never a
 * person's key. Ed25519 signs deterministically, so each step's bytes are fixed.
 */
class HostVaultShareVectorTest {

    /** The vector's key seed: made for the vector, never a person's. */
    static final String SEED = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    static final String HOST = "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s";

    static final String LOGIN = "builder";

    static final String PUBLIC_BLOB =
            "0000000b7373682d656432353531390000002003a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8";

    static final String SIGNED_DATA =
            "5353485349470000000c706f72656c6f2d7661756c7400000000000000067368613531320000004036cf1f788a6a3b380949fa3701cee14fd226413ff3e67937acc3ef65b730ea44a8ded47c8f49cbc18bd770cc0ec615d8bd73bacd56a92cc96faf2602671a7d95";

    static final String SIGNATURE_BLOB =
            "53534853494700000001000000330000000b7373682d656432353531390000002003a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b80000000c706f72656c6f2d7661756c740000000000000006736861353132000000530000000b7373682d65643235353139000000404727d4497aa83f483deec835366b10e242bb4cf6a83f47e1a6fde736bedfb8a2477fc0ace6b453af545df15b2d9ecc1d302b2c018d5756ac61acb27cad5fa80a";

    static final String SHARE = "f894c8fd19308ab206813eb64af1b821653623f006e6a4b95e3bd3d5d8843828";

    private static final HexFormat HEX = HexFormat.of();

    /** The vector's key, recording what it is asked to sign. */
    private static final class Recording implements AgentKey {

        private final SigningKey key = new SigningKey(HEX.parseHex(SEED), "vector-only");

        private final List<byte[]> signed = new ArrayList<>();

        @Override
        public String comment() {
            return key.comment();
        }

        @Override
        public byte[] keyBlob() {
            return key.keyBlob();
        }

        @Override
        public String authorizedKeysLine() {
            return key.authorizedKeysLine();
        }

        @Override
        public byte[] sign(byte[] data, int flags) {
            signed.add(data.clone());
            return key.sign(data, flags);
        }
    }

    @Test
    void theShareIsDerivedByTheWrittenRule() {
        final Recording key = new Recording();

        final byte[] share = HostVaultShare.derive(key, HOST, LOGIN);

        assertThat(HEX.formatHex(key.keyBlob())).as("the key's public blob").isEqualTo(PUBLIC_BLOB);
        assertThat(key.signed).as("one signature").hasSize(1);
        assertThat(HEX.formatHex(key.signed.getFirst()))
                .as("the SSHSIG signed data over 'host=<fp> account=<login>'")
                .isEqualTo(SIGNED_DATA);
        final byte[] blob =
                SshSignature.signBlob(
                        key,
                        ("host=" + HOST + " account=" + LOGIN).getBytes(StandardCharsets.UTF_8),
                        "porelo-vault");
        assertThat(HEX.formatHex(blob)).as("the binary SSHSIG blob").isEqualTo(SIGNATURE_BLOB);
        assertThat(HEX.formatHex(share)).as("the 32-byte share").isEqualTo(SHARE);
        assertThat(share)
                .as("HKDF-SHA256 over the whole blob, empty salt, info porelo-vault-share-v1")
                .isEqualTo(hkdf(blob));
    }

    @Test
    void theSignedDataIsTheRulesSshsigPreimage() throws Exception {
        final java.io.ByteArrayOutputStream expected = new java.io.ByteArrayOutputStream();
        expected.write("SSHSIG".getBytes(StandardCharsets.US_ASCII));
        SshWire.writeString(expected, "porelo-vault");
        SshWire.writeString(expected, new byte[0]);
        SshWire.writeString(expected, "sha512");
        SshWire.writeString(
                expected,
                java.security.MessageDigest.getInstance("SHA-512")
                        .digest(
                                ("host=" + HOST + " account=" + LOGIN)
                                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(HEX.formatHex(expected.toByteArray()))
                .as("the rule, written out, gives the vector's signed data")
                .isEqualTo(SIGNED_DATA);
    }

    @Test
    @Tag("documents")
    void theDocsStateTheVectorThisTestPins() throws Exception {
        final String page = Files.readString(Path.of("..", "doc", "credentials.md"));

        assertThat(page)
                .as("the vector in doc/credentials.md")
                .contains(
                        "key seed       " + SEED,
                        "public blob    " + PUBLIC_BLOB,
                        "host           " + HOST,
                        "login          " + LOGIN,
                        "signed data    " + SIGNED_DATA,
                        "signature blob " + SIGNATURE_BLOB,
                        "share          " + SHARE);
    }

    private static byte[] hkdf(byte[] blob) {
        final HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
        generator.init(
                new HKDFParameters(
                        blob,
                        new byte[0],
                        "porelo-vault-share-v1".getBytes(StandardCharsets.UTF_8)));
        final byte[] share = new byte[32];
        generator.generateBytes(share, 0, share.length);
        return share;
    }
}
