package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards a project's backup key: read, at most one per key, and allowed one change alone. */
class ProjectBackupSignerTest {

    private static final String MAIN = key(1);
    private static final String BACKUP = key(2);
    private static final String NEW = key(3);

    private static String key(final int seed) {
        final byte[] blob = new byte[51];
        java.util.Arrays.fill(blob, (byte) seed);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob);
    }

    private static String file(final String signers) {
        return """
        project:
          name: demo
          security_class: guarded
          signers:
        %simage:
          base_image: ubuntu:24.04
        """
                .formatted(signers);
    }

    private static final String ONE = file("    - \"" + MAIN + " admin@example.org\"\n");

    private static final String BACKED =
            ProjectSigners.withBackup(ONE, BACKUP, ProjectSigners.fingerprint(MAIN), "2026-10-31");

    @Test
    void a_backup_key_is_read_beside_the_key_it_backs_and_is_no_signer() {
        final List<ProjectSigners.Signer> entries = ProjectSigners.entries(BACKED, "p.yml");

        assertThat(entries)
                .containsExactly(
                        new ProjectSigners.Signer(MAIN, false, "", ""),
                        new ProjectSigners.Signer(
                                BACKUP, true, ProjectSigners.fingerprint(MAIN), "2026-10-31"));
        assertThat(ProjectReader.signers(BACKED, "p.yml"))
                .as("a backup key is no signer")
                .containsExactly(MAIN);
        assertThat(ProjectSigners.soleWithoutBackup(ProjectSigners.entries(ONE, "p.yml")))
                .isEqualTo(MAIN);
        assertThat(ProjectSigners.soleWithoutBackup(entries)).isNull();
        assertThat(ProjectReader.read(new java.io.StringReader(BACKED), "p.yml").name())
                .isEqualTo("demo");
    }

    @Test
    void a_second_backup_key_for_the_same_key_is_refused() {
        final String twice =
                ProjectSigners.withBackup(
                        BACKED, NEW, ProjectSigners.fingerprint(MAIN), "2026-10-31");

        assertThatThrownBy(() -> ProjectSigners.entries(twice, "p.yml"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("second backup key");
        assertThatThrownBy(
                        () ->
                                ProjectSigners.entries(
                                        ProjectSigners.withBackup(
                                                ONE,
                                                BACKUP,
                                                ProjectSigners.fingerprint(NEW),
                                                "2026-10-31"),
                                        "p.yml"))
                .as("a backup key backs a key that is listed")
                .hasMessageContaining("no key listed");
        assertThatThrownBy(
                        () ->
                                ProjectSigners.entries(
                                        ProjectSigners.withBackup(
                                                ONE,
                                                MAIN,
                                                ProjectSigners.fingerprint(MAIN),
                                                "2026-10-31"),
                                        "p.yml"))
                .as("a key is not its own backup")
                .hasMessageContaining("already listed");
    }

    @Test
    void a_backup_key_may_replace_the_key_it_backs_and_nothing_else() {
        final String replaced = ProjectSigners.replacing(BACKED, MAIN, NEW, BACKUP);

        assertThat(ProjectSigners.entries(replaced, "p.yml"))
                .containsExactly(
                        new ProjectSigners.Signer(NEW, false, "", ""),
                        new ProjectSigners.Signer(
                                BACKUP, true, ProjectSigners.fingerprint(NEW), "2026-10-31"));
        assertThat(ProjectSigners.backedOnly(BACKED, replaced, BACKUP)).isNull();

        assertThat(
                        ProjectSigners.backedOnly(
                                BACKED,
                                replaced.replace(
                                        "security_class: guarded", "security_class: online"),
                                BACKUP))
                .as("anything outside the signers")
                .contains("beyond project.signers");
        final String added =
                file(
                        "    - \""
                                + MAIN
                                + "\"\n    - \""
                                + NEW
                                + "\"\n    - key: \""
                                + BACKUP
                                + "\"\n      backup: true\n      backs: \""
                                + ProjectSigners.fingerprint(MAIN)
                                + "\"\n      since: \"2026-10-31\"\n");
        assertThat(ProjectSigners.backedOnly(BACKED, added, BACKUP))
                .as("a key added beside the one it backs")
                .contains("beyond replacing");
        final String elsewhere =
                file(
                        "    - \""
                                + NEW
                                + "\"\n    - key: \""
                                + BACKUP
                                + "\"\n      backup: true\n      backs: \""
                                + ProjectSigners.fingerprint(NEW)
                                + "\"\n      since: \"2026-11-01\"\n");
        assertThat(ProjectSigners.backedOnly(BACKED, elsewhere, BACKUP))
                .as("the backup entry changed beyond pointing at the new key")
                .contains("backup keys");
        assertThat(ProjectSigners.backedOnly(BACKED, replaced, NEW))
                .as("a key that is no backup key")
                .contains("no backup key");
    }
}
