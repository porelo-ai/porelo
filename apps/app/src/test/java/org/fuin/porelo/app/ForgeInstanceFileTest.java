package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgeInstanceFileTest {

    private static final URI PULL = URI.create("https://github.com/shop/app/pull/42");

    @TempDir Path dir;

    @Test
    void aMachineWithoutTheFileKnowsNoForge() throws Exception {
        final ForgeInstanceFile forges = forges();

        assertThat(forges.all()).as("no forge is known").isEmpty();
        assertThat(forges.of(PULL)).as("no address belongs to one").isEmpty();
        assertThat(forges.loosened()).as("nothing to name").isEmpty();
    }

    @Test
    void aRecordWithoutTheSettingIsReadExtended() throws Exception {
        write("forges:\n  - host: github.com\n    adapter: github\n");

        assertThat(forges().of(PULL))
                .as("the instance, its comments through the extended check")
                .contains(new ForgeInstance("github.com", "github", CommentReading.EXTENDED, null));
    }

    @Test
    void onlyAReadableOwnerOnlyFileThatSaysTrustedIsReadTrusted() throws Exception {
        write("forges:\n  - host: github.com\n    adapter: github\n    comments: trusted\n");

        assertThat(forges().of(PULL).map(ForgeInstance::reading))
                .as("trusted, said explicitly in an owner-only file")
                .contains(CommentReading.TRUSTED);
        assertThat(forges().loosened()).as("nothing loosened").isEmpty();
    }

    @Test
    void aGroupWritableFileThatSaysTrustedIsReadExtendedAndSaysWhy() throws Exception {
        write("forges:\n  - host: github.com\n    adapter: github\n    comments: trusted\n");
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-rw----"));

        assertThat(forges().of(PULL).map(ForgeInstance::reading))
                .as("another could have written it, so nothing is trusted")
                .contains(CommentReading.EXTENDED);
        assertThat(forges().loosened())
                .as("the reason names the file")
                .hasValueSatisfying(why -> assertThat(why).contains(file().toString()));
    }

    @Test
    void aWorldWritableFileIsReadExtendedToo() throws Exception {
        write("forges:\n  - host: github.com\n    adapter: github\n    comments: trusted\n");
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-r--rw-"));

        assertThat(forges().of(PULL).map(ForgeInstance::reading))
                .as("anyone could have written it")
                .contains(CommentReading.EXTENDED);
    }

    @Test
    void aMalformedFileCannotBeReadAndNothingIsReadTrusted() throws Exception {
        write("forges: [host: github.com, adapter: github, comments: trusted\n");

        assertThatThrownBy(() -> forges().of(PULL))
                .as("of refuses a file it cannot read as a whole")
                .isInstanceOf(ForgeFileUnreadable.class)
                .hasMessageContaining(file().toString());
        assertThatThrownBy(() -> forges().all())
                .as("all refuses it too")
                .isInstanceOf(ForgeFileUnreadable.class);
        write("- github.com\n");
        assertThatThrownBy(() -> forges().all())
                .as("a file that is not a mapping of forges")
                .isInstanceOf(ForgeFileUnreadable.class);
    }

    @Test
    void oneRecordThatCannotBeReadStandsApartAndTheOthersStand() throws Exception {
        write(
                """
                forges:
                  - host: github.com
                    adapter: github
                    comments: trusted
                  - host: forge.example
                    adapter: Not An Adapter
                    comments: trusted
                  - host: "bad host/"
                    adapter: gitlab
                  - host: codeberg.org
                    adapter: forgejo
                    comments: perhaps
                """);

        final List<ForgeInstance> all = forges().all();

        assertThat(all).as("every record, in the file's order").hasSize(4);
        assertThat(all.getFirst())
                .as("the readable one stands")
                .isEqualTo(new ForgeInstance("github.com", "github", CommentReading.TRUSTED, null));
        assertThat(all.subList(1, 4))
                .as("each unreadable one says why and is read extended")
                .allSatisfy(
                        instance -> {
                            assertThat(instance.unreadable()).as("why").isNotBlank();
                            assertThat(instance.adapter()).as("no adapter").isNull();
                            assertThat(instance.reading())
                                    .as("reading")
                                    .isEqualTo(CommentReading.EXTENDED);
                        });
    }

    @Test
    void anAddressIsResolvedByItsHostAloneAndAnotherHostGetsNothing() throws Exception {
        write("forges:\n  - host: github.com\n    adapter: github\n");
        final ForgeInstanceFile forges = forges();

        assertThat(forges.of(URI.create("https://github.com:443/shop/app/pull/7")))
                .as("a port does not change the host")
                .isPresent();
        assertThat(forges.of(URI.create("https://github.com.evil.example/shop/app/pull/7")))
                .as("a host that merely starts with it")
                .isEmpty();
        assertThat(forges.of(URI.create("https://gitlab.com/shop/app/-/merge_requests/7")))
                .as("another host")
                .isEmpty();
    }

    @Test
    void theCredentialIsTheHostsOwnEntryNeverThePlainGitToken() throws Exception {
        write("forges:\n  - host: github.com\n    adapter: github\n");

        assertThat(forges().of(PULL).map(ForgeInstance::credential))
                .as("the per-host entry")
                .contains("git.token.github.com");
        assertThat(forges().all())
                .extracting(ForgeInstance::credential)
                .as("the plain git.token is never offered")
                .doesNotContain("git.token");
    }

    @Test
    void settingWritesTheWholeFileOwnerOnlyAndKeepsAnUnreadableRecordForItsOwner()
            throws Exception {
        installed("github", "gitlab");
        write(
                """
                forges:
                  - host: forge.example
                    adapter: Not An Adapter
                """);
        final ForgeInstanceFile forges = forges();

        forges.set("github.com", "github", CommentReading.TRUSTED);
        forges.set("gitlab.com", "gitlab", CommentReading.EXTENDED);
        forges.set("github.com", "github", CommentReading.EXTENDED);

        assertThat(Files.getPosixFilePermissions(file()))
                .as("owner-only")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(forges.all())
                .as("the unreadable record kept, the others in the order they were added")
                .extracting(ForgeInstance::host, ForgeInstance::reading)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "forge.example", CommentReading.EXTENDED),
                        org.assertj.core.groups.Tuple.tuple("github.com", CommentReading.EXTENDED),
                        org.assertj.core.groups.Tuple.tuple("gitlab.com", CommentReading.EXTENDED));
        assertThat(Files.list(dir).map(path -> path.getFileName().toString()))
                .as("no temporary file is left beside it")
                .allMatch(
                        name ->
                                name.equals(ForgeInstanceFile.NAME)
                                        || name.endsWith(".lock")
                                        || name.equals("builds"));
    }

    @Test
    void settingALoosenedFileWritesEveryOtherRecordAsItWasRead() throws Exception {
        installed("github", "gitlab");
        write("forges:\n  - host: github.com\n    adapter: github\n    comments: trusted\n");
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-rw-rw-"));

        forges().set("gitlab.com", "gitlab", CommentReading.EXTENDED);

        assertThat(forges().of(PULL).map(ForgeInstance::reading))
                .as("a trusted another could have written is not made genuine by a rewrite")
                .contains(CommentReading.EXTENDED);
    }

    @Test
    void settingRefusesAnAdapterNotInstalledAndAHostThatIsNoDnsName() throws Exception {
        installed("github");
        final ForgeInstanceFile forges = forges();

        assertThatThrownBy(() -> forges.set("gitlab.com", "gitlab", CommentReading.EXTENDED))
                .as("an adapter not installed")
                .isInstanceOfSatisfying(
                        AdapterNotInstalled.class,
                        refused -> assertThat(refused.adapter()).as("named").isEqualTo("gitlab"));
        assertThatThrownBy(() -> forges.set("not a host", "github", CommentReading.EXTENDED))
                .as("a host that is no DNS name")
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(AdapterNotInstalled.class);
        assertThat(file()).as("nothing was written").doesNotExist();
    }

    private ForgeInstanceFile forges() {
        return new ForgeInstanceFile(file(), new ForgeAdapters(List.of(dir.resolve("builds"))));
    }

    private Path file() {
        return dir.resolve(ForgeInstanceFile.NAME);
    }

    private void write(final String text) throws IOException {
        Files.writeString(file(), text, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-------"));
    }

    private void installed(final String... names) throws IOException {
        final Path builds = Files.createDirectories(dir.resolve("builds"));
        for (final String name : names) {
            final Path adapter = builds.resolve(name);
            Files.writeString(adapter, "#!/bin/sh\nexit 0\n");
            Files.setPosixFilePermissions(adapter, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
