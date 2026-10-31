package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against an owners file read loosely, or quoted back to whoever reads a refusal. */
class RegistryOwnersTest {
    private static final String KEY =
            "ssh-ed25519 " + Base64.getEncoder().encodeToString(SshWireKey.blob(0));

    private static final String BACKUP =
            "ssh-ed25519 " + Base64.getEncoder().encodeToString(SshWireKey.blob(1));

    private static final String OTHER =
            "ssh-ed25519 " + Base64.getEncoder().encodeToString(SshWireKey.blob(2));

    private static final String GOOD =
            "owners:\n  - login: alice\n    key: \""
                    + KEY
                    + "\"\n  - login: alice\n    key: \""
                    + BACKUP
                    + "\"\n    backup: true\nhost_admins:\n  walk6m:\n    - login: michael\n      key: \""
                    + KEY
                    + "\"\n";

    /** An Ed25519 public key blob, built as OpenSSH writes one. */
    private static final class SshWireKey {
        static byte[] blob(int last) {
            final byte[] type = "ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            final java.nio.ByteBuffer buffer =
                    java.nio.ByteBuffer.allocate(4 + type.length + 4 + 32);
            final byte[] point = new byte[32];
            point[31] = (byte) last;
            buffer.putInt(type.length).put(type).putInt(32).put(point);
            return buffer.array();
        }
    }

    /** What the rule judges by is read as written. */
    @Test
    void reads_owners_and_host_admins() {
        final RegistryOwners owners = RegistryOwners.read(GOOD, "owners.yml");
        assertThat(owners.owners())
                .containsExactly(
                        new RegistryOwners.Entry("alice", KEY, false),
                        new RegistryOwners.Entry("alice", BACKUP, true));
        assertThat(owners.hostAdmins().get("walk6m"))
                .containsExactly(new RegistryOwners.Entry("michael", KEY, false));
    }

    /** A loosely read file would let a field nobody checks change who may sign. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "unknown-top-level",
                "duplicate-top-level",
                "duplicate-login",
                "second-backup",
                "duplicate-key",
                "backup-of-nobody",
                "rsa-key",
                "porelo-login",
                "uppercase-host",
                "anchor",
                "quoted-backup",
                "no-owners",
                "empty-owners"
            })
    void refuses_a_malformed_owners_file_naming_only_line_and_column(String damage) {
        final String text =
                switch (damage) {
                    case "unknown-top-level" -> GOOD + "admins: {}\n";
                    case "duplicate-top-level" -> GOOD + "owners: []\n";
                    case "duplicate-login" ->
                            GOOD.replace(
                                    "    backup: true\n",
                                    "  - login: alice\n    key: \"" + BACKUP + "\"\n");
                    case "second-backup" ->
                            GOOD.replace(
                                    "    backup: true\n",
                                    "    backup: true\n  - login: alice\n    key: \""
                                            + OTHER
                                            + "\"\n    backup: true\n");
                    case "duplicate-key" -> GOOD.replace(BACKUP, KEY);
                    case "backup-of-nobody" ->
                            GOOD.replace("  - login: alice\n    key: \"" + KEY + "\"\n", "");
                    case "rsa-key" ->
                            GOOD.replace(
                                    BACKUP + "\"\n    backup", "ssh-rsa AAAAB3Nza\"\n    backup");
                    case "porelo-login" -> GOOD.replace("login: alice", "login: porelo-x");
                    case "uppercase-host" -> GOOD.replace("walk6m:", "Walk6m:");
                    case "anchor" -> GOOD.replace("login: alice", "login: &a alice");
                    case "quoted-backup" -> GOOD.replace("backup: true", "backup: \"true\"");
                    case "no-owners" -> GOOD.substring(GOOD.indexOf("host_admins:"));
                    default -> "owners: []\n";
                };
        assertThat(text).as("the damage applied").isNotEqualTo(GOOD);
        assertThatThrownBy(() -> RegistryOwners.read(text, "owners.yml"))
                .isInstanceOf(CardException.class)
                .hasMessageStartingWith("owners.yml")
                .hasMessageNotContaining("alice")
                .hasMessageNotContaining("Walk6m")
                .hasMessageNotContaining("porelo-x");
    }
}
