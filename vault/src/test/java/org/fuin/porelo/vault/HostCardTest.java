package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A host's card and its one signature. Harm guarded: a card taken on its word sends a person's ssh,
 * git and messages to whoever wrote the file; a card read loosely - a port that is not the number
 * signed, a field nobody declared - reaches a place the host admin never named.
 */
class HostCardTest {

    private final SigningKey admin = SigningKey.generate("admin");

    private final SigningKey other = SigningKey.generate("other");

    private final String hostKey = PersonCard.keyLine(SigningKey.generate("host"));

    private String card() {
        return HostCard.write("walk-9", "walk-9.example.org", 22, hostKey, 3000, 2222, 8448, admin);
    }

    private static String pin(final SigningKey key) {
        return PersonCard.fingerprint(PersonCard.keyLine(key));
    }

    private static void refused(final String changed, final String why) {
        assertThatThrownBy(() -> HostCard.read(changed, "test"))
                .as(changed)
                .isInstanceOf(CardException.class)
                .hasMessageContaining(why);
    }

    @Test
    void reads_what_it_writes_with_every_field() {

        final HostCard card = HostCard.read(card(), "test");

        assertThat(card.host()).isEqualTo("walk-9");
        assertThat(card.address()).isEqualTo("walk-9.example.org");
        assertThat(card.sshPort()).isEqualTo(22);
        assertThat(card.sshHostKey()).isEqualTo(hostKey);
        assertThat(card.forgeWebPort()).isEqualTo(3000);
        assertThat(card.forgeGitPort()).isEqualTo(2222);
        assertThat(card.matrixPort()).isEqualTo(8448);
        assertThat(card.signer()).isEqualTo(pin(admin));
        assertThatCode(() -> card.checkSignedBy(pin(admin))).doesNotThrowAnyException();
    }

    @Test
    void a_card_signed_by_another_key_names_both() {
        final HostCard card = HostCard.read(card(), "test");

        assertThatThrownBy(() -> card.checkSignedBy(pin(other)))
                .isInstanceOf(CardException.class)
                .hasMessageContaining(pin(admin))
                .hasMessageContaining(pin(other));
    }

    @Test
    void a_body_changed_after_signing_does_not_verify() {
        // Same signer, another port: the signature covers the body's exact bytes.
        final HostCard forged = HostCard.read(card().replace("port: 8448", "port: 8449"), "test");

        assertThat(forged.signer()).isEqualTo(pin(admin));
        assertThatThrownBy(() -> forged.checkSignedBy(pin(admin)))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("does not verify");
    }

    @Test
    void a_signature_in_another_namespace_does_not_verify() {
        final String file = card();
        final String body = file.substring(0, file.indexOf("-----BEGIN"));
        final String elsewhere =
                SshSignature.sign(
                        admin,
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "porelo-message");

        assertThatThrownBy(() -> HostCard.read(body + elsewhere, "test").checkSignedBy(pin(admin)))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("does not verify");
    }

    @Test
    void refuses_a_host_name_that_is_not_a_lower_case_dns_label() {
        final String card = card();

        for (final String host :
                List.of("Walk_9", "walk_9", "-walk", "walk-", "walk.example", "w".repeat(64))) {
            refused(card.replace("\"walk-9\"", "\"" + host + "\""), "is not a host name");
        }
        assertThat(HostCard.read(card.replace("\"walk-9\"", "\"9\""), "test").host())
                .isEqualTo("9");
    }

    @Test
    void refuses_a_missing_port() {
        final String card = card();

        refused(card.replace("    port: 8448\n", ""), "'services.matrix.port' is missing");
        refused(card.replace("    git_port: 2222\n", ""), "'services.forge.git_port' is missing");
        refused(card.replace("ssh_port: 22\n", ""), "'ssh_port' is missing");
        refused(card.replace("ssh_port: 22\n", "ssh_port:\n"), "'ssh_port' is missing");
    }

    @Test
    void refuses_a_port_that_is_not_a_plain_number_from_1_to_65535() {
        final String card = card();

        for (final String port :
                List.of(
                        "\"22\"", "'22'", "0", "65536", "022", "0x16", "+22", "22.0", "2_2",
                        "-1")) {
            refused(card.replace("ssh_port: 22\n", "ssh_port: " + port + "\n"), "is not a port");
        }
        assertThat(
                        HostCard.read(card.replace("ssh_port: 22\n", "ssh_port: 65535\n"), "test")
                                .sshPort())
                .isEqualTo(65535);
    }

    @Test
    void refuses_any_nesting_but_the_services() {
        final String card = card();

        for (final String changed :
                List.of(
                        card.replace("services:\n", "services:\n  ldap:\n    port: 389\n"),
                        card.replace("    web_port: 3000\n", "    web_port:\n      at: 3000\n"),
                        card.replace("host: \"walk-9\"", "host:\n  name: \"walk-9\""),
                        card.replace("ssh_port: 22\n", "ssh_port: 22\nforge:\n  web_port: 1\n"),
                        card.replace("  matrix:\n    port: 8448\n", "  port: 8448\n"),
                        card.replace("ssh_port: 22\n", "ssh_port: [22]\n"))) {
            assertThatThrownBy(() -> HostCard.read(changed, "test"))
                    .as(changed)
                    .isInstanceOf(CardException.class);
        }
        refused(
                card.replace("services:\n", "services:\n  ldap:\n    port: 389\n"),
                "'services.ldap' is not a field of a host card");
        refused(
                card.replace("  matrix:\n    port: 8448\n", "  matrix: 8448\n"),
                "'services.matrix' is not a mapping");
    }

    @Test
    void refuses_a_merge_key_an_anchor_an_alias_and_a_tag() {
        final String card = card();

        for (final String changed :
                List.of(
                        card.replace("  matrix:\n", "  <<: {matrix: {port: 1}}\n  matrix:\n"),
                        card.replace("  forge:\n", "  forge: &f\n")
                                .replace("  matrix:\n    port: 8448\n", "  matrix: *f\n"),
                        card.replace("services:\n", "services: !!map\n"),
                        card.replace("ssh_port: 22", "ssh_port: !!int 22"),
                        card.replace("ssh_port: 22", "ssh_port: &p 22")
                                .replace("port: 8448", "port: *p"))) {
            assertThatThrownBy(() -> HostCard.read(changed, "test"))
                    .as(changed)
                    .isInstanceOf(CardException.class);
        }
    }

    @Test
    void refuses_a_field_given_twice_at_any_level() {
        final String card = card();

        refused(card.replace("ssh_port: 22\n", "ssh_port: 22\nssh_port: 23\n"), "given twice");
        refused(
                card.replace("    port: 8448\n", "    port: 8448\n  matrix:\n    port: 1\n"),
                "given twice");
    }

    @Test
    void refuses_a_control_character_and_a_host_key_that_is_not_one() {
        final String card = card();
        final String blob = hostKey.substring("ssh-ed25519 ".length());

        refused(card.replace("walk-9.example.org", "walk-9\\e[2J"), "control character");
        refused(card.replace("walk-9.example.org", "walk 9"), "is not an address");
        refused(card.replace(hostKey, "ssh-rsa " + blob), "'ssh_host_key' is not");
        refused(card.replace(hostKey, hostKey + " root@walk-9"), "'ssh_host_key' is not");
        refused(card.replace(hostKey, "ssh-ed25519 AAAA"), "'ssh_host_key' is not");
    }

    @Test
    void accepts_a_host_key_of_another_kind_that_names_its_own_type() {
        // As ssh-keygen -t ecdsa wrote it, without the comment.
        final String ecdsa =
                "ecdsa-sha2-nistp256"
                        + " AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBBRLMhV9bcbtrrhIAfY2"
                        + "+ZaI/XL+MfmXZyuZdSVVsl3DSgB5oKQEEiuywxsl2nEUbK49Gu2khaHf9mcgZ7cSe6Q=";

        assertThat(HostCard.read(card().replace(hostKey, ecdsa), "test").sshHostKey())
                .isEqualTo(ecdsa);
    }

    @Test
    void refuses_no_signature_and_a_second_one() {
        final String file = card();
        final String signature = file.substring(file.indexOf("-----BEGIN"));

        refused(file.substring(0, file.indexOf("-----BEGIN")), "carries no signature");
        refused(file + signature, "more than one signature");
        refused(file + "\n" + signature, "only its signature may follow");
        refused(file + "extra: 1\n", "only its signature may follow");
        refused(file.substring(0, file.indexOf("-----END")), "is not closed");
        refused(
                file.replace("-----BEGIN SSH SIGNATURE-----\n", "-----BEGIN SSH SIGNATURE-----"),
                "not armored");
    }

    @Test
    void refuses_an_address_that_is_not_one_name_or_ip_address() {
        final String card = card();

        for (final String address :
                List.of(
                        "walk-9.example.org:9999",
                        "foo..bar",
                        "walk-9.example.org-",
                        "999.999.999.999",
                        "gggg::1",
                        "fe80::1%eth0",
                        "-oProxyCommand=sh",
                        "*,*",
                        "%h",
                        "user@evil")) {
            refused(card.replace("walk-9.example.org", address), "is not an address");
        }
    }

    @Test
    void accepts_an_ip_address_of_either_kind() {
        assertThat(HostCard.address("192.0.2.1")).isTrue();
        assertThat(HostCard.address("::1")).isTrue();
        assertThat(HostCard.address("2001:db8::7")).isTrue();
    }

    @Test
    void refuses_a_host_key_blob_short_or_with_bytes_after_it() {
        final String card = card();
        final byte[] blob =
                java.util.Base64.getDecoder().decode(hostKey.substring("ssh-ed25519 ".length()));
        final byte[] longer = java.util.Arrays.copyOf(blob, blob.length + 1);
        final java.nio.ByteBuffer shortBlob = java.nio.ByteBuffer.allocate(4 + 11 + 4 + 16);
        shortBlob
                .putInt(11)
                .put("ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        shortBlob.putInt(16).put(new byte[16]);

        for (final byte[] each : List.of(longer, shortBlob.array())) {
            refused(
                    card.replace(
                            hostKey,
                            "ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(each)),
                    "'ssh_host_key' is not");
        }
        refused(card.replace(hostKey, "ssh-rsa AAAAB3NzaC1yc2E="), "'ssh_host_key' is not");
        refused(
                card.replace(hostKey, "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY="),
                "'ssh_host_key' is not");
    }

    @Test
    void accepts_an_rsa_and_an_ecdsa_host_key_made_here() throws Exception {
        final java.security.KeyPairGenerator rsa =
                java.security.KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        final var rsaKey =
                (java.security.interfaces.RSAPublicKey) rsa.generateKeyPair().getPublic();
        final java.security.KeyPairGenerator ec = java.security.KeyPairGenerator.getInstance("EC");
        ec.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
        final var ecKey = (java.security.interfaces.ECPublicKey) ec.generateKeyPair().getPublic();

        for (final String line :
                List.of(
                        rsaLine(
                                rsaKey.getPublicExponent().toByteArray(),
                                rsaKey.getModulus().toByteArray()),
                        ecdsaLine(
                                "nistp384",
                                point(ecKey.getW().getAffineX(), ecKey.getW().getAffineY(), 48)))) {
            assertThat(HostCard.read(card().replace(hostKey, line), "test").sshHostKey())
                    .isEqualTo(line);
        }
    }

    @Test
    void refuses_an_rsa_host_key_ssh_keygen_would_not_take() throws Exception {
        final java.security.KeyPairGenerator rsa =
                java.security.KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        final var key = (java.security.interfaces.RSAPublicKey) rsa.generateKeyPair().getPublic();
        final byte[] e = key.getPublicExponent().toByteArray();
        final byte[] n = key.getModulus().toByteArray();
        final byte[] padded = new byte[n.length + 1];
        System.arraycopy(n, 0, padded, 1, n.length);
        final byte[] negative = java.util.Arrays.copyOfRange(n, 1, n.length);
        final java.security.KeyPairGenerator small =
                java.security.KeyPairGenerator.getInstance("RSA");
        small.initialize(1024);
        final var weak =
                (java.security.interfaces.RSAPublicKey) small.generateKeyPair().getPublic();

        for (final String line :
                List.of(
                        rsaLine(new byte[] {0}, new byte[256]),
                        rsaLine(e, padded),
                        rsaLine(e, negative),
                        rsaLine(new byte[] {0, 1, 0, 1}, n),
                        rsaLine(new byte[] {4}, n),
                        rsaLine(e, weak.getModulus().toByteArray()))) {
            refused(card().replace(hostKey, line), "'ssh_host_key' is not");
        }
    }

    @Test
    void refuses_an_ecdsa_host_key_whose_point_is_not_on_its_curve() throws Exception {
        final java.security.KeyPairGenerator ec = java.security.KeyPairGenerator.getInstance("EC");
        ec.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        final var key = (java.security.interfaces.ECPublicKey) ec.generateKeyPair().getPublic();
        final byte[] good = point(key.getW().getAffineX(), key.getW().getAffineY(), 32);
        final byte[] moved = good.clone();
        moved[64] ^= 1;
        final byte[] compressed = good.clone();
        compressed[0] = 2;

        assertThat(HostCard.read(card().replace(hostKey, ecdsaLine("nistp256", good)), "test"))
                .isNotNull();
        for (final String line :
                List.of(
                        ecdsaLine("nistp256", new byte[65]),
                        ecdsaLine("nistp256", moved),
                        ecdsaLine("nistp256", compressed),
                        ecdsaLine("nistp384", good))) {
            refused(card().replace(hostKey, line), "'ssh_host_key' is not");
        }
    }

    @Test
    void refuses_an_address_a_resolver_reads_otherwise_than_it_is_written() {
        for (final String address :
                List.of(
                        "[::1]",
                        "0177.0.0.1",
                        "127.1",
                        "2130706433",
                        "0x7f.0.0.1",
                        "0x7f000001",
                        "fe80::1%1",
                        "::ffff:127.0.0.1",
                        "1.2.3.4.")) {
            assertThat(HostCard.address(address)).as(address).isFalse();
        }
        assertThat(HostCard.address("10.0.0.1")).isTrue();
        assertThat(HostCard.address("255.255.255.255")).isTrue();
        assertThat(HostCard.address("walk-9.example.org")).isTrue();
    }

    @Test
    void refuses_an_armor_with_anything_but_base64_lines_inside() {
        final String file = card();
        final int begin = file.indexOf("-----BEGIN SSH SIGNATURE-----\n") + 30;
        final int end = file.indexOf("-----END");

        refused(file.substring(0, begin) + file.substring(end), "not armored");
        refused(file.substring(0, begin) + "\t" + file.substring(begin), "not armored");
        refused(file.substring(0, begin) + "\n" + file.substring(begin), "not armored");
        refused(file.substring(0, end - 1) + file.substring(end), "not armored");
        refused(
                file.substring(0, begin)
                        + "-----BEGIN SSH SIGNATURE-----\n"
                        + file.substring(begin),
                "not armored");
    }

    @Test
    void refuses_a_signature_with_bytes_after_it() {
        final String file = card();
        final int begin = file.indexOf("-----BEGIN SSH SIGNATURE-----\n") + 30;
        final int end = file.indexOf("-----END");
        final byte[] blob =
                java.util.Base64.getDecoder().decode(file.substring(begin, end).replace("\n", ""));
        final String longer =
                java.util.Base64.getEncoder()
                        .encodeToString(java.util.Arrays.copyOf(blob, blob.length + 4));
        final StringBuilder wrapped = new StringBuilder();
        for (int at = 0; at < longer.length(); at += 70) {
            wrapped.append(longer, at, Math.min(at + 70, longer.length())).append('\n');
        }
        final HostCard card =
                HostCard.read(file.substring(0, begin) + wrapped + file.substring(end), "test");

        assertThatThrownBy(() -> card.checkSignedBy(pin(admin))).isInstanceOf(CardException.class);
    }

    @Test
    void refuses_a_signature_with_something_reserved_or_after_its_inner_signature() {
        final String file = card();

        assertThatCode(() -> HostCard.read(rebuilt(file, 0, 0), "test").checkSignedBy(pin(admin)))
                .as("rebuilt unchanged, it verifies")
                .doesNotThrowAnyException();
        for (final String changed : List.of(rebuilt(file, 1, 0), rebuilt(file, 0, 3))) {
            final HostCard card = HostCard.read(changed, "test");
            assertThatThrownBy(() -> card.checkSignedBy(pin(admin)))
                    .isInstanceOf(CardException.class);
        }
        // Bytes after the key's 32 are another key blob for the same key; trusted by its own pin.
        final HostCard longer = HostCard.read(rebuilt(file, 0, 0, 2), "test");
        assertThatThrownBy(() -> longer.checkSignedBy(longer.signer()))
                .isInstanceOf(CardException.class);
    }

    @Test
    void refuses_a_signature_in_base64_not_spelled_as_ssh_keygen_spells_it() {
        final String file = card();
        final int begin = file.indexOf("-----BEGIN SSH SIGNATURE-----\n") + 30;
        final int end = file.indexOf("-----END");
        final String body = file.substring(begin, end);

        // ssh-keygen pads its last line; an Ed25519 SSHSIG is 236 characters with one '='.
        assertThat(body).contains("=");
        assertThatThrownBy(
                        () ->
                                HostCard.read(file.replace("=\n-----END", "\n-----END"), "test")
                                        .checkSignedBy(pin(admin)))
                .isInstanceOf(CardException.class);
        refused(
                file.substring(0, begin) + body.replace("\n", "") + "\n" + file.substring(end),
                "not armored");
        refused(file.substring(0, begin) + "AA==\nBB==\n" + file.substring(end), "not armored");
        refused(
                file.substring(0, begin) + body.replaceFirst("\n", "") + file.substring(end),
                "not armored");
        // The two bits before '=' are unused: setting one names the same bytes, spelled otherwise.
        final int pad = body.indexOf('=') - 1;
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        final String flipped =
                body.substring(0, pad)
                        + alphabet.charAt(alphabet.indexOf(body.charAt(pad)) + 1)
                        + body.substring(pad + 1);
        assertThat(java.util.Base64.getMimeDecoder().decode(flipped))
                .isEqualTo(java.util.Base64.getMimeDecoder().decode(body));
        assertThatThrownBy(
                        () ->
                                HostCard.read(
                                                file.substring(0, begin)
                                                        + flipped
                                                        + file.substring(end),
                                                "test")
                                        .checkSignedBy(pin(admin)))
                .isInstanceOf(CardException.class);
    }

    @Test
    void refuses_more_host_keys_ssh_keygen_would_not_take() throws Exception {
        final java.security.KeyPairGenerator rsa =
                java.security.KeyPairGenerator.getInstance("RSA");
        rsa.initialize(3072);
        final var big = (java.security.interfaces.RSAPublicKey) rsa.generateKeyPair().getPublic();
        final byte[] e = big.getPublicExponent().toByteArray();
        final byte[] n = big.getModulus().toByteArray();
        final java.security.KeyPairGenerator ec = java.security.KeyPairGenerator.getInstance("EC");
        ec.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        final var p256 = (java.security.interfaces.ECPublicKey) ec.generateKeyPair().getPublic();
        final byte[] good = point(p256.getW().getAffineX(), p256.getW().getAffineY(), 32);

        for (final String line :
                List.of(
                        rsaLine(new byte[] {1}, n),
                        rsaLine(e, java.util.Arrays.copyOfRange(n, 1, n.length)),
                        rsaLine(e, n) + "AAAA",
                        "ssh-rsa " + blob("ssh-rsa", e, n, new byte[] {1}),
                        "ecdsa-sha2-nistp256 "
                                + blob(
                                        "ecdsa-sha2-nistp256",
                                        "nistp521"
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.US_ASCII),
                                        good),
                        "ecdsa-sha2-nistp256 "
                                + blob(
                                        "ecdsa-sha2-nistp256",
                                        "nistp256"
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.US_ASCII),
                                        good,
                                        new byte[] {1}))) {
            refused(card().replace(hostKey, line), "'ssh_host_key' is not");
        }
        // A coordinate not reduced mod p names the same point; ssh-keygen refuses it. On nistp521
        // the coordinate plus p always fits its 66 bytes.
        ec.initialize(new java.security.spec.ECGenParameterSpec("secp521r1"));
        final var p521 = (java.security.interfaces.ECPublicKey) ec.generateKeyPair().getPublic();
        final java.math.BigInteger p =
                ((java.security.spec.ECFieldFp) p521.getParams().getCurve().getField()).getP();
        final java.math.BigInteger x5 = p521.getW().getAffineX();
        final java.math.BigInteger y5 = p521.getW().getAffineY();
        refused(
                card().replace(hostKey, ecdsaLine("nistp521", point(x5.add(p), y5, 66))),
                "'ssh_host_key' is not");
        refused(
                card().replace(hostKey, ecdsaLine("nistp521", point(x5, y5.add(p), 66))),
                "'ssh_host_key' is not");
    }

    @Test
    void accepts_a_nistp521_host_key_made_here() throws Exception {
        final java.security.KeyPairGenerator ec = java.security.KeyPairGenerator.getInstance("EC");
        ec.initialize(new java.security.spec.ECGenParameterSpec("secp521r1"));
        final var key = (java.security.interfaces.ECPublicKey) ec.generateKeyPair().getPublic();
        final String line =
                ecdsaLine("nistp521", point(key.getW().getAffineX(), key.getW().getAffineY(), 66));

        assertThat(HostCard.read(card().replace(hostKey, line), "test").sshHostKey())
                .isEqualTo(line);
    }

    /**
     * The card with its SSHSIG blob taken apart and put together again: {@code reserved} bytes in
     * the reserved field, {@code after} bytes after the inner Ed25519 signature.
     */
    private static String rebuilt(final String file, final int reserved, final int after) {
        return rebuilt(file, reserved, after, 0);
    }

    /** As {@link #rebuilt(String, int, int)}, with {@code keyAfter} bytes after the public key. */
    private static String rebuilt(
            final String file, final int reserved, final int after, final int keyAfter) {
        final int begin = file.indexOf("-----BEGIN SSH SIGNATURE-----\n") + 30;
        final int end = file.indexOf("-----END");
        final java.nio.ByteBuffer in =
                java.nio.ByteBuffer.wrap(
                        java.util.Base64.getDecoder()
                                .decode(file.substring(begin, end).replace("\n", "")));
        final byte[] magic = new byte[6];
        in.get(magic);
        final int version = in.getInt();
        final byte[] key = field(in);
        final byte[] namespace = field(in);
        field(in);
        final byte[] hash = field(in);
        final byte[] signature = field(in);
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(magic);
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(version).array());
        for (final byte[] each :
                List.of(
                        java.util.Arrays.copyOf(key, key.length + keyAfter),
                        namespace,
                        new byte[reserved],
                        hash,
                        java.util.Arrays.copyOf(signature, signature.length + after))) {
            out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(each.length).array());
            out.writeBytes(each);
        }
        final String armored = java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        final StringBuilder wrapped = new StringBuilder();
        for (int at = 0; at < armored.length(); at += 70) {
            wrapped.append(armored, at, Math.min(at + 70, armored.length())).append('\n');
        }
        return file.substring(0, begin) + wrapped + file.substring(end);
    }

    private static byte[] field(final java.nio.ByteBuffer in) {
        final byte[] out = new byte[in.getInt()];
        in.get(out);
        return out;
    }

    private static String rsaLine(final byte[] exponent, final byte[] modulus) {
        return "ssh-rsa " + blob("ssh-rsa", exponent, modulus);
    }

    private static String ecdsaLine(final String curve, final byte[] point) {
        return "ecdsa-sha2-"
                + curve
                + " "
                + blob(
                        "ecdsa-sha2-" + curve,
                        curve.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        point);
    }

    private static String blob(final String type, final byte[]... fields) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());
        out.writeBytes(name);
        for (final byte[] field : fields) {
            out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(field.length).array());
            out.writeBytes(field);
        }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** An uncompressed point, each coordinate as wide as its curve's field. */
    private static byte[] point(
            final java.math.BigInteger x, final java.math.BigInteger y, final int size) {
        final byte[] out = new byte[1 + 2 * size];
        out[0] = 4;
        final byte[] xs = x.toByteArray();
        final byte[] ys = y.toByteArray();
        final int xl = Math.min(xs.length, size);
        final int yl = Math.min(ys.length, size);
        System.arraycopy(xs, xs.length - xl, out, 1 + size - xl, xl);
        System.arraycopy(ys, ys.length - yl, out, 1 + 2 * size - yl, yl);
        return out;
    }

    @Test
    void writes_no_card_it_would_not_read() {
        assertThatThrownBy(
                        () ->
                                HostCard.write(
                                        "Walk_9",
                                        "walk-9.example.org",
                                        22,
                                        hostKey,
                                        3000,
                                        2222,
                                        8448,
                                        admin))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("is not a host name");
        assertThatThrownBy(
                        () ->
                                HostCard.write(
                                        "walk-9",
                                        "walk-9.example.org",
                                        22,
                                        hostKey,
                                        0,
                                        2222,
                                        8448,
                                        admin))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("is not a port");
    }
}
