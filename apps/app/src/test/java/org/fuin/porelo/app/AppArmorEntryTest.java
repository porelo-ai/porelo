package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.wire.AppArmorProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AppArmorEntry}: a binary not under {@code /usr} enters {@code porelo} itself, by
 * asking for it on the next exec and re-executing itself with the same arguments.
 */
class AppArmorEntryTest {

    @TempDir Path dir;

    private final List<List<String>> executed = new ArrayList<>();

    private Path proc() {
        return dir.resolve("proc");
    }

    private Path binary() {
        return dir.resolve("home/.local/bin/porelod");
    }

    private AppArmorProfile profile() {
        return AppArmorProfile.at(proc(), dir.resolve("sys"));
    }

    private Path attribute(String name) {
        return proc().resolve("thread-self/attr/apparmor/" + name);
    }

    /** An account install on a machine whose porelo profile is loaded, the process outside it. */
    @BeforeEach
    void outsideTheLoadedProfile() throws IOException {
        final Path enabled = dir.resolve("sys/module/apparmor/parameters/enabled");
        Files.createDirectories(enabled.getParent());
        Files.writeString(enabled, "Y\n");
        final Path loaded = dir.resolve("sys/kernel/security/apparmor/policy/profiles/porelo.4");
        Files.createDirectories(loaded);
        Files.writeString(loaded.resolve("name"), "porelo\n");
        Files.createDirectories(attribute("current").getParent());
        Files.writeString(attribute("current"), "unconfined\n");
        Files.createDirectories(binary().getParent());
        Files.writeString(binary(), "");
        Files.createDirectories(proc().resolve("self"));
        Files.createSymbolicLink(proc().resolve("self/exe"), binary());
        Files.write(
                proc().resolve("self/cmdline"),
                "porelod\0--serve\0a b\0".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void reExecutesItselfWithTheSameArgumentsOnceTheProfileIsAskedFor() throws IOException {
        AppArmorEntry.enter(profile(), proc(), executed::add);

        assertThat(Files.readString(attribute("exec"))).isEqualTo("exec porelo");
        assertThat(executed)
                .containsExactly(List.of(binary().toRealPath().toString(), "--serve", "a b"));
    }

    @Test
    void doesNothingInAProcessAlreadyInTheProfile() throws IOException {

        // What keeps the re-executed binary from re-executing again.
        Files.writeString(attribute("current"), "porelo (unconfined)\n");

        AppArmorEntry.enter(profile(), proc(), executed::add);

        assertThat(executed).isEmpty();
        assertThat(attribute("exec")).doesNotExist();
    }

    @Test
    void doesNothingWhereTheProfileIsNotLoaded() throws IOException {
        Files.delete(dir.resolve("sys/kernel/security/apparmor/policy/profiles/porelo.4/name"));

        AppArmorEntry.enter(profile(), proc(), executed::add);

        assertThat(executed).isEmpty();
    }

    @Test
    void runsOnAsItIsWhereTheKernelRefusesTheProfile() throws IOException {

        // Under no_new_privs, say: a refused entry must not stop the binary, and doctor says it is
        // outside the profile.
        Files.delete(attribute("current"));
        Files.delete(attribute("current").getParent());
        Files.writeString(attribute("current").getParent(), "not a directory");

        AppArmorEntry.enter(profile(), proc(), executed::add);

        assertThat(executed).isEmpty();
    }

    @Test
    void runsOnAsItIsWhenTheExecFails() {
        assertThatCode(
                        () ->
                                AppArmorEntry.enter(
                                        profile(),
                                        proc(),
                                        arguments -> {
                                            throw new IllegalStateException("execvp returned -1");
                                        }))
                .doesNotThrowAnyException();
    }

    @Test
    void asksForNothingWhenItsOwnBinaryIsGone() throws IOException {

        // Replaced by an update while it ran: the link names a deleted file, and re-executing
        // whatever now has that name would run another version.
        Files.delete(binary());

        AppArmorEntry.enter(profile(), proc(), executed::add);

        assertThat(executed).isEmpty();
        assertThat(attribute("exec")).doesNotExist();
    }
}
