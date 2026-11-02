package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link ContainerName}. */
class ContainerNameTest {

    @Test
    void tellsALoginContainerApartFromATask() {

        // Reported from the test machine: 'porelo task list' showed a login container, which has no
        // workspace, no gate and no clearance, so every column of that list lies about it.
        final String login = ContainerName.login();
        assertThat(ContainerName.isPorelo(login)).isTrue();
        assertThat(ContainerName.isLogin(login)).isTrue();
        assertThat(ContainerName.isTask(login)).isFalse();
    }

    @Test
    void countsAnOrdinaryContainerAsATask() {

        final String task =
                ContainerName.of(
                        new org.fuin.porelo.core.project.Project(
                                "utils4j",
                                "Utils",
                                org.fuin.porelo.core.project.SecurityClass.GUARDED,
                                "ubuntu:24.04",
                                null),
                        "shell");
        assertThat(task).isEqualTo("porelo-utils4j-shell");
        assertThat(ContainerName.isPorelo(task)).isTrue();
        assertThat(ContainerName.isLogin(task)).isFalse();
        assertThat(ContainerName.isTask(task)).isTrue();
    }

    @Test
    void namesOneContainerPerTaskSoStartingTwiceFindsTheFirst() {

        // The whole point of dropping the run id. It used to carry the launching process's pid, so
        // two invocations never shared a container and nothing could map a task to one - 'start'
        // would build a second beside the one that was named.
        final org.fuin.porelo.core.project.Project project =
                new org.fuin.porelo.core.project.Project(
                        "utils4j",
                        "",
                        org.fuin.porelo.core.project.SecurityClass.GUARDED,
                        "ubuntu:24.04",
                        null);

        assertThat(ContainerName.of(project, "shell"))
                .isEqualTo(ContainerName.of(project, "shell"));
    }

    @Test
    void refusesATaskNamePodmanOrGitWouldRefuseLater() {

        // Reported from the interface: 'Foo Bar' was accepted, the image was built, and podman
        // refused the container name only then - leaving policy, resolver and sidecar behind.
        assertThat(ContainerName.refusal("utils4j", "Foo Bar"))
                .get()
                .asString()
                .contains("not a task name")
                .contains("'foo-bar' would do");
        for (final String bad :
                new String[] {
                    "Foo", "foo_bar", "foo.bar", "foo..bar", "foo/bar", "-foo", "foo-", ""
                }) {
            assertThat(ContainerName.refusal("utils4j", bad)).as(bad).isPresent();
        }
        // Only digits is the shape that marks a login container.
        assertThat(ContainerName.refusal("login", "123"))
                .get()
                .asString()
                .contains("needs a letter");

        for (final String good : new String[] {"shell", "a", "build-2", "2nd-try", "foo-bar"}) {
            assertThat(ContainerName.refusal("utils4j", good)).as(good).isEmpty();
        }
    }

    @Test
    void refusesTheNamesAMessageGivesTheRoomsPeopleAndAPerson() {

        // Its key would be certified for a sender the reader holds, or one that passes as a
        // person's own note.
        for (final String reserved : new String[] {"people", "person"}) {
            assertThat(ContainerName.refusal("p", reserved))
                    .as(reserved)
                    .get()
                    .asString()
                    .contains("not a task name");
        }
        assertThat(ContainerName.refusal("p", "people-2"))
                .as("only the names themselves")
                .isEmpty();
    }

    @Test
    void aPeerNamesATaskOfAnotherProjectExactlyWhenAStartCouldMakeThatTask() {

        // A peer no task start can make is one whose every message is held, and an agent's
        // filter copies the peer rule: both must take what a start takes and nothing else.
        final java.util.regex.Pattern peer =
                java.util.regex.Pattern.compile(
                        org.fuin.porelo.core.project.Mail.Peer.NAME_PATTERN);
        final String[] projects = {"p", "web-app", "a--b", "9", "x".repeat(55), "x".repeat(56)};
        final String[] tasks = {
            "build",
            "b",
            "7",
            "123",
            "people",
            "person",
            "people-2",
            "person1",
            "a1",
            "-a",
            "a-",
            "A",
            "a".repeat(48),
            "a".repeat(49),
            "a".repeat(50),
            "a".repeat(54),
            "a".repeat(55),
            "porelo-p-build",
            "porelo-web-app-build",
            "porelo-build",
            "porelo"
        };
        int taken = 0;
        int refused = 0;
        for (final String project : projects) {
            for (final String task : tasks) {
                final boolean starts =
                        project.matches(org.fuin.porelo.core.project.Project.NAME_PATTERN)
                                && ContainerName.refusal(project, task).isEmpty()
                                // A start takes its container's spelling for the task it names.
                                && ContainerName.taskFrom(project, task).equals(task);
                assertThat(peer.matcher("task:" + project + ":" + task).matches())
                        .as(
                                "task:"
                                        + project
                                        + ":"
                                        + task
                                        + ", which a start "
                                        + (starts ? "makes" : "refuses"))
                        .isEqualTo(starts);
                if (starts) {
                    taken++;
                } else {
                    refused++;
                }
            }
        }
        assertThat(taken).as("names a start makes, checked").isGreaterThan(10);
        assertThat(refused).as("names a start refuses, checked").isGreaterThan(10);
    }

    @Test
    void refusesATaskNameWhoseContainerNameWouldNotFitASocketPath() {

        // 'porelo-' + 'utils4j' + '-' is 15 characters, so 49 more make exactly 64.
        final String fits = "a".repeat(49);
        assertThat(ContainerName.refusal("utils4j", fits)).isEmpty();
        assertThat(ContainerName.refusal("utils4j", fits + "a"))
                .get()
                .asString()
                .contains("65 characters")
                .contains("64");
        // Without a project the length cannot be known, and is left to the start that has one.
        assertThat(ContainerName.refusal("", fits + "a")).isEmpty();
    }

    @Test
    void takesAContainerNameGivenAsATaskAsTheTaskItNames() {

        // Reported on the VM: 'start porelo-utils4j-shell' for a stopped task created a second one,
        // porelo-utils4j-porelo-utils4j-shell, with an empty workspace.
        assertThat(ContainerName.taskFrom("utils4j", "porelo-utils4j-shell")).isEqualTo("shell");
        assertThat(ContainerName.taskFrom("utils4j", "shell")).isEqualTo("shell");
        // Another project's container is not this project's task.
        assertThat(ContainerName.taskFrom("utils4j", "porelo-other-shell"))
                .isEqualTo("porelo-other-shell");
        // The prefix alone names no task.
        assertThat(ContainerName.taskFrom("utils4j", "porelo-utils4j-"))
                .isEqualTo("porelo-utils4j-");
    }

    @Test
    void handsBackTheTaskInsideAContainerName() {

        // Carried to an interface rather than derived by it: the rule relating the two belongs here
        // and has already changed once.
        assertThat(ContainerName.taskIn("utils4j", "porelo-utils4j-shell")).isEqualTo("shell");
        assertThat(ContainerName.taskIn("utils4j", "porelo-other-shell")).isEmpty();
    }

    @Test
    void countsNothingOutsidePoreloAsEither() {

        // The prefix is what keeps a cleanup off somebody else's containers.
        assertThat(ContainerName.isPorelo("postgres")).isFalse();
        assertThat(ContainerName.isTask("postgres")).isFalse();
        assertThat(ContainerName.isLogin("login-1")).isFalse();
    }

    @Test
    void namesALoginAfterTheProjectItNeverHas() {

        assertThat(ContainerName.login()).startsWith("porelo-login-");
        assertThat(ContainerName.login().substring("porelo-login-".length())).containsOnlyDigits();
    }

    @Test
    void doesNotMistakeATaskInAProjectCalledLoginForOne() {

        // 'login' passes the project name check, so this name is reachable. Matching on the prefix
        // alone would hide a real task from 'task list' and make attach and stop refuse it - a
        // project nobody could work in, for the sake of its name.
        assertThat(ContainerName.isLogin("porelo-login-shell-25471")).isFalse();
        assertThat(ContainerName.isTask("porelo-login-shell-25471")).isTrue();

        // Even a task named entirely of digits keeps its hyphen before the run id.
        assertThat(ContainerName.isLogin("porelo-login-42-7")).isFalse();
        assertThat(ContainerName.isTask("porelo-login-42-7")).isTrue();
    }

    @Test
    void doesNotTakeTheBarePrefixForALogin() {

        // Nothing produces this name; it is here because "starts with the prefix" would say yes.
        assertThat(ContainerName.isLogin("porelo-login-")).isFalse();
    }
}
