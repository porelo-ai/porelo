package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AppArmorProfile}, over a {@code /proc} and {@code /sys} of the test's own, laid
 * out as Ubuntu 26.04's kernel lays them out.
 *
 * <p>The four answers {@code doctor} gives, and the one in between: profiles loaded, but this
 * process not in {@code porelo}.
 */
class AppArmorProfileTest {

    @TempDir Path dir;

    private Path proc() {
        return dir.resolve("proc");
    }

    private Path sys() {
        return dir.resolve("sys");
    }

    private AppArmorProfile profile() {
        return new AppArmorProfile(proc(), sys());
    }

    /** The module parameter, {@code Y} where AppArmor runs. */
    private void enabled(String value) throws IOException {
        final Path parameter = sys().resolve("module/apparmor/parameters/enabled");
        Files.createDirectories(parameter.getParent());
        Files.writeString(parameter, value + "\n");
    }

    /**
     * A loaded profile, as securityfs shows it: a directory named after the profile and a number,
     * holding the profile's name.
     */
    private void loaded(String name, int number) throws IOException {
        final Path entry =
                sys().resolve("kernel/security/apparmor/policy/profiles/" + name + "." + number);
        Files.createDirectories(entry);
        Files.writeString(entry.resolve("name"), name + "\n");
        Files.writeString(entry.resolve("mode"), "enforce\n");
    }

    /** The feature file a kernel that mediates unix sockets has. */
    private void mediatesUnixSockets() throws IOException {
        // A file holding "yes", measured on Ubuntu 26.04 (kernel 7.0): not a directory, as the
        // feature directories above it are.
        final Path feature = sys().resolve("kernel/security/apparmor/features/network/af_unix");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "yes\n");
    }

    /** This process's label, as the kernel prints it: name, mode in brackets, a line end. */
    private void current(String printed) throws IOException {
        final Path attribute = proc().resolve("self/attr/apparmor/current");
        Files.createDirectories(attribute.getParent());
        Files.writeString(attribute, printed);
    }

    /** A machine where everything is in place: the answer {@code installed}. */
    private void installed() throws IOException {
        enabled("Y");
        loaded("porelo", 7);
        loaded("porelo-task", 8);
        mediatesUnixSockets();
        current("porelo (unconfined)\n");
    }

    @Test
    void namesTheTwoProfilesTheFilesDefine() {
        assertThat(AppArmorProfile.HOST).isEqualTo("porelo");
        assertThat(AppArmorProfile.TASK).isEqualTo("porelo-task");
    }

    @Test
    void needsNothingWhereAppArmorDoesNotRun() throws IOException {

        // Fedora: no module at all. A kernel with AppArmor built in but switched off has the
        // parameter and says N.
        assertThat(profile().state()).isEqualTo(AppArmorProfile.State.NOT_NEEDED);
        enabled("N");
        assertThat(profile().state()).isEqualTo(AppArmorProfile.State.NOT_NEEDED);
        assertThat(profile().taskProfile()).isNull();
    }

    @Test
    void callsTheProfileMissingWhereAppArmorRunsWithoutIt() throws IOException {
        enabled("Y");
        mediatesUnixSockets();
        loaded("firefox", 3);
        // A name that begins like the task's is another profile.
        loaded("porelo-task-old", 4);

        assertThat(profile().state()).isEqualTo(AppArmorProfile.State.MISSING);
        assertThat(profile().taskProfile()).isNull();
    }

    @Test
    void callsTheLockAbsentWhereTheKernelDoesNotMediateUnixSockets() throws IOException {

        // The profile loads there, and the parser drops the peer rule: a task would be confined
        // and still connect to anything.
        installed();
        Files.delete(sys().resolve("kernel/security/apparmor/features/network/af_unix"));

        assertThat(profile().state()).isEqualTo(AppArmorProfile.State.ABSENT_ON_KERNEL);
        assertThat(profile().taskProfile()).isNull();
    }

    @Test
    void callsItInstalledWhereEverythingIsInPlaceAndThisProcessWearsTheLabel() throws IOException {
        installed();

        assertThat(profile().state()).isEqualTo(AppArmorProfile.State.INSTALLED);
        assertThat(profile().taskProfile()).isEqualTo("porelo-task");
    }

    @Test
    void saysSoWhenThisProcessIsOutsideTheLabelItsSocketsMustWear() throws IOException {

        // A task put into its profile would be refused every socket this process binds, so the
        // task stays as it is and doctor says why.
        installed();
        current("unconfined\n");

        assertThat(profile().state()).isEqualTo(AppArmorProfile.State.OUTSIDE);
        assertThat(profile().taskProfile()).isNull();
    }

    @Test
    void keepsAStackOutsideTheHostProfileAndNamesEveryPart() throws IOException {
        installed();
        current("porelo//&unconfined (unconfined)\n");
        final Path thread = proc().resolve("thread-self/attr/apparmor/current");
        Files.createDirectories(thread.getParent());
        Files.writeString(thread, "porelo (unconfined)\n");

        assertThat(profile().state())
                .as("a stacked process cannot serve sockets to a task in porelo-task")
                .isEqualTo(AppArmorProfile.State.OUTSIDE);
        assertThat(profile().label())
                .as("the reason must retain the whole stack")
                .isEqualTo("porelo//&unconfined");
        assertThat(profile().taskProfile()).as("its own task would refuse its sockets").isNull();
    }

    @Test
    void readsTheProcessLabelEvenWhenTheCallingThreadHasAnother() throws IOException {
        installed();
        final Path thread = proc().resolve("thread-self/attr/apparmor/current");
        Files.createDirectories(thread.getParent());
        Files.writeString(thread, "porelo//&unconfined (unconfined)\n");

        assertThat(profile().state())
                .as("the process wears plain porelo")
                .isEqualTo(AppArmorProfile.State.INSTALLED);
        assertThat(profile().taskProfile())
                .as("plain porelo can serve confined tasks")
                .isEqualTo("porelo-task");
    }

    @Test
    void readsTheLabelWithoutItsMode() throws IOException {
        current("porelo (unconfined)\n");
        assertThat(profile().label()).isEqualTo("porelo");

        current("porelo-task (enforce)\0");
        assertThat(profile().label()).isEqualTo("porelo-task");

        current("unconfined\n");
        assertThat(profile().label()).isEqualTo("unconfined");
    }

    @Test
    void readsNoLabelWhereTheAttributeIsNotThere() {

        // A kernel before 5.8 has no apparmor directory under attr.
        assertThat(profile().label()).isEmpty();
    }

    @Test
    void asksAProcessThatIsOutsideTheHostProfileToEnterIt() throws IOException {
        installed();
        current("unconfined\n");

        assertThat(profile().shouldEnter()).isTrue();
    }

    @Test
    void asksNothingOfAProcessAlreadyInTheHostProfile() throws IOException {

        // The check that keeps a re-executed binary from re-executing again.
        installed();

        assertThat(profile().shouldEnter()).isFalse();
    }

    @Test
    void neverAsksAStackedProcessToReEnterTheHostProfile() throws IOException {
        installed();
        current("porelo//&unconfined (unconfined)\n");

        assertThat(profile().shouldEnter())
                .as("another entry would repeat the same stack")
                .isFalse();
    }

    @Test
    void asksNothingWhereTheHostProfileIsNotLoaded() throws IOException {
        enabled("Y");
        loaded("porelo-task", 8);
        current("unconfined\n");

        assertThat(profile().shouldEnter()).isFalse();
    }

    @Test
    void asksNothingWhereAppArmorDoesNotRun() throws IOException {
        loaded("porelo", 7);
        current("unconfined\n");

        assertThat(profile().shouldEnter()).isFalse();
    }

    @Test
    void entersAProfileOnTheNextExecByWritingTheThreadsAttribute() throws IOException {
        final Path attribute = proc().resolve("thread-self/attr/apparmor/exec");
        Files.createDirectories(attribute.getParent());

        assertThat(profile().enterOnExec("porelo")).isTrue();
        assertThat(Files.readString(attribute, StandardCharsets.US_ASCII)).isEqualTo("exec porelo");
    }

    @Test
    void saysSoWhenTheKernelRefusesTheEntry() {

        // No attribute to write to: the kernel's refusal looks the same from here.
        assertThat(profile().enterOnExec("porelo")).isFalse();
    }

    @Test
    void entersAProfileNowByWritingTheThreadsCurrentAttribute() throws IOException {

        // The relay's connecting thread: the kernel then checks its connect as a task's.
        current("porelo (unconfined)\n");
        final Path thread = proc().resolve("thread-self/attr/apparmor/current");
        Files.createDirectories(thread.getParent());

        assertThat(profile().changeProfile("porelo-task")).isTrue();
        assertThat(
                        Files.readString(
                                proc().resolve("thread-self/attr/apparmor/current"),
                                StandardCharsets.US_ASCII))
                .isEqualTo("changeprofile porelo-task");
        assertThat(profile().label())
                .as("changing the relay thread leaves the process label alone")
                .isEqualTo("porelo");
    }

    @Test
    void saysSoWhenTheKernelRefusesTheChange() {
        assertThat(profile().changeProfile("porelo-task")).isFalse();
    }
}
