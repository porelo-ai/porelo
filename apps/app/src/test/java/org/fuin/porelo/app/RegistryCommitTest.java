package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.porelo.organization.git.RegistryGit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the reading of a registry commit that runs no program and trusts no spelling of Git's. */
class RegistryCommitTest {
    @TempDir Path dir;
    private RegistryCheckout registry;

    @BeforeEach
    void checkout() throws Exception {
        registry = new RegistryCheckout(dir);
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
    }

    private String raw(String commit) {
        return registry.runner
                .runOrFail(
                        RegistryGit.git(
                                registry.repo, java.util.Map.of(), "cat-file", "commit", commit))
                .standardOutput();
    }

    /** A signature checked here must be the named key's, over exactly the commit's bytes. */
    @Test
    void a_signature_verifies_for_its_own_key_over_the_commits_bytes_alone() throws Exception {
        final String raw = raw(registry.commit("alice", "File people/alice.card"));
        final RegistryCommit commit = RegistryCommit.parse(raw);
        assertThat(commit.signedBy(registry.pin("alice"))).isTrue();
        assertThat(commit.signedBy(registry.pin("bob"))).as("another key").isFalse();
        assertThat(
                        RegistryCommit.parse(raw.replace("File people", "File other"))
                                .signedBy(registry.pin("alice")))
                .as("another message")
                .isFalse();
        assertThat(RegistryCommit.parse(raw(registry.commit("bob", "next"))).parent())
                .isEqualTo(registry.git("rev-parse", "HEAD^"));
    }

    /** A PGP or X.509 signature is checked by a program the checkout names; it is no signature. */
    @Test
    void a_signature_of_another_kind_or_given_twice_is_none() {
        final String head =
                "tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\n"
                        + "author A <a@example.org> 0 +0000\ncommitter A <a@example.org> 0 +0000\n";
        assertThat(
                        RegistryCommit.parse(
                                        head
                                                + "gpgsig -----BEGIN PGP SIGNATURE-----\n \n x\n -----END PGP SIGNATURE-----\n\nm\n")
                                .signature())
                .isNull();
        final String ssh =
                "gpgsig -----BEGIN SSH SIGNATURE-----\n x\n -----END SSH SIGNATURE-----\n";
        assertThat(RegistryCommit.parse(head + ssh + "\nm\n").signature())
                .isEqualTo("-----BEGIN SSH SIGNATURE-----\nx\n-----END SSH SIGNATURE-----\n");
        assertThat(RegistryCommit.parse(head + ssh + ssh + "\nm\n").signature()).isNull();
        assertThat(RegistryCommit.parse(head + ssh + "\nm\n").payload()).isEqualTo(head + "\nm\n");
    }

    /** Bytes that are no UTF-8 are not the text a signature is checked over here. */
    @Test
    void a_commit_of_bytes_that_are_no_utf8_has_no_signature_but_its_trailers() {
        final byte[] raw =
                ("tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\n"
                                + "gpgsig -----BEGIN SSH SIGNATURE-----\n x\n -----END SSH SIGNATURE-----\n"
                                + "\nnote \u0080\n\nPorelo-Change-Id: a-1\n")
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        final RegistryCommit commit = RegistryCommit.parse(raw);
        assertThat(commit.signature()).isNull();
        assertThat(commit.carries("Porelo-Change-Id", "a-1")).isTrue();
    }

    /**
     * A trailer counts only as Porelo writes it, once; Git's reading would trim, unfold and remap
     * keys as the checkout configures, and a second digest could hide behind a separator.
     */
    @Test
    void a_trailer_matches_once_and_exactly_as_written() {
        final String head = "tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\n\n";
        assertThat(
                        RegistryCommit.parse(head + "File x\n\nPorelo-Change-Id: a-1\n")
                                .carries("Porelo-Change-Id", "a-1"))
                .isTrue();
        for (String message :
                new String[] {
                    "File x\n\nPorelo-Change-Id: a-1\nPorelo-Change-Id: a-1\n",
                    "File x\n\nPorelo-Change-Id: a-1 \n",
                    "File x\n\nPorelo-Change-Id: a-1\r\n",
                    "File x\n\nPorelo-Change-Id:a-1\n",
                    "File x\n\nporelo-change-id: a-1\n"
                }) {
            assertThat(RegistryCommit.parse(head + message).carries("Porelo-Change-Id", "a-1"))
                    .as(message)
                    .isFalse();
        }
        assertThat(
                        RegistryCommit.parse(
                                        head
                                                + "Porelo-Change-Digest: d\nPorelo-Change-Digest: \u001e\n")
                                .once("Porelo-Change-Digest"))
                .as("a second, empty digest")
                .isNull();
    }
}
