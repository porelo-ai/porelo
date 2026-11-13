package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link ContainerName}. */
class ContainerNameTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
            value = {
                "website, work, ws, x, porelo-ws_x",
                "website, work, ws, NULL, porelo-ws_work",
                "website, work, NULL, x, porelo-website_x",
                "website, work, NULL, NULL, porelo-website_work",
                "porelo, work, ws, x, porelo_x",
                "porelo, work, ws, NULL, porelo_work"
            },
            nullValues = "NULL")
    void usesEachRecordedShortSpellingIndependently(
            String project, String task, String projectShort, String taskShort, String expected)
            throws Exception {
        // The recorded spelling must identify the same container on every lookup.
        assertThat(ContainerName.encoded(project, task, projectShort, taskShort, 1000L))
                .as("each absent spelling falls back to its logical name")
                .isEqualTo(expected);
        assertThat(ContainerName.isEncoded(project, task, projectShort, taskShort, expected))
                .as("recorded-name verification uses the same codec")
                .isEqualTo(true);
        assertThat(
                        ContainerName.isEncoded(
                                project, task, projectShort, taskShort, expected + "-other"))
                .as("a different container cannot be adopted")
                .isEqualTo(false);
    }

    @Test
    void encodesDistinctOwnersWithoutHyphenAmbiguity() {
        // A collision can make a second project resume or overwrite the first task.
        assertThat(ContainerName.encoded("porelo", "pi-x-123", 1000)).isEqualTo("porelo_pi-x-123");
        assertThat(ContainerName.encoded("web", "app-x", 1000)).isEqualTo("porelo-web_app-x");
        assertThat(ContainerName.encoded("web-app", "x", 1000)).isEqualTo("porelo-web-app_x");
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (String project : java.util.List.of("porelo", "web", "web-app", "a--b", "9")) {
            for (String task : java.util.List.of("app-x", "x", "x-123", "a--b", "9a")) {
                assertThat(names.add(ContainerName.encoded(project, task, 1000))).isTrue();
            }
        }
        assertThat(names).hasSize(25);
    }

    @Test
    void budgetsEverySocketForTheUidAndNeverTruncatesAnExplicitName() {
        // A daemon socket that is too long leaves a task without its authorized endpoint.
        final String fits = ContainerName.encoded("web", "a".repeat(52), 4294967295L);
        assertThat(
                        ("/run/user/4294967295/porelo/mcp/" + fits + ".daemon.sock")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .hasSize(107);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ContainerName.encoded("web", "a".repeat(53), 4294967295L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("52");
        assertThat(ContainerName.encoded("web", "a".repeat(58), 1000)).endsWith("a".repeat(58));
    }

    @Test
    void aNewTaskOfProjectPoreloIsItsTaskBehindAnUnderscoreAndNeverALogin() {
        // 'porelo-login-123' of project porelo would have been hidden and swept as a login.
        assertThat(ContainerName.encoded("porelo", "pi", 1000))
                .as("project porelo's two first words shortened to one")
                .isEqualTo("porelo_pi");
        final String login = ContainerName.encoded("porelo", "login-123", 1000);
        assertThat(login).isEqualTo("porelo_login-123");
        assertThat(ContainerName.isLogin(login)).as("no login container's shape").isFalse();
        assertThat(ContainerName.isTask(login)).as("a task like any other").isTrue();
        assertThat(ContainerName.isTask("porelo-porelo-login-123")).isTrue();
    }

    @Test
    void aTaskOfProjectPoreloMadeBeforeKeepsItsNameThroughItsIdentity() {
        // Its gate, mail and keys are kept under the name it was made with.
        assertThat(ContainerName.isEncoded("porelo", "pi", null, null, "porelo-pi"))
                .as("the name project porelo's tasks were made under before")
                .isTrue();
        assertThat(ContainerName.isEncoded("porelo", "pi", null, null, "porelo_pi")).isTrue();
        assertThat(ContainerName.isEncoded("web", "pi", null, null, "porelo-pi"))
                .as("another project's task never had it")
                .isFalse();
    }

    @Test
    void eitherBeginningIsPorelosAndATasksAndNothingElseIs() {
        // A porelo_ task taken for nobody's would be missed by list, stop and cleanup alike.
        for (final String name :
                java.util.List.of(
                        "porelo_pi", "porelo-uc-shell", "porelo-uc_shell", "porelo_a-b")) {
            assertThat(ContainerName.isPorelo(name)).as(name).isTrue();
            assertThat(ContainerName.isTask(name)).as(name).isTrue();
            assertThat(name.matches(ContainerName.NAME_PATTERN)).as(name).isTrue();
        }
        for (final String name :
                java.util.List.of(
                        "porelo",
                        "porelo_",
                        "porelo-",
                        "porelox-pi",
                        "poreloPi",
                        "other-porelo_pi",
                        "porelo_Pi",
                        "porelo_pi/x",
                        "porelo__pi",
                        "porelo-_pi")) {
            assertThat(name.matches(ContainerName.NAME_PATTERN)).as(name).isFalse();
        }
        assertThat(ContainerName.isPorelo("porelox-pi")).isFalse();
        assertThat(ContainerName.isTask("poreloPi")).isFalse();
    }

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
    void aTooLongTaskNameIsRefusedWithAShorterOneThatFits() {

        // A person told only the count retries blind; a name that fits ends the retries.
        final String project = "example-shop-api-templates-project";
        final String task = "example-shop-api-templates";
        final String refusal = ContainerName.refusal(project, task).orElseThrow();

        assertThat(refusal)
                .as("the room left beside this project")
                .contains("at most 22 characters")
                .as("a start that works, cut at a word")
                .contains("'porelo task start example-shop-api'");
        assertThat(ContainerName.refusal(project, "example-shop-api"))
                .as("the suggested name is one the same check takes")
                .isEmpty();
    }

    @Test
    void aProjectThatLeavesNoRoomForATaskSaysSoInsteadOfSuggestingOne() {

        // 'porelo-' + 56 + '-' is 64 before any task: no task name can help.
        final String refusal = ContainerName.refusal("p".repeat(56), "a").orElseThrow();

        assertThat(refusal)
                .as("the project's name is what must change")
                .contains("project.yml")
                .doesNotContain("porelo task start");
    }

    @Test
    void aNameWithCharactersNoTaskMayHaveSuggestsOnlyANameEveryRuleTakes() {

        // The lowercased suggestion was checked against the pattern alone, so it could be a name
        // the next rule refuses.
        assertThat(ContainerName.refusal("uc", "123 "))
                .get()
                .asString()
                .as("digits only mark a login container")
                .doesNotContain("would do");
        assertThat(ContainerName.refusal("uc", "People"))
                .get()
                .asString()
                .as("the room's people")
                .doesNotContain("would do");
        assertThat(ContainerName.refusal("uc", "Porelo-Fix"))
                .get()
                .asString()
                .as("a container's beginning")
                .doesNotContain("would do");
        assertThat(ContainerName.refusal("p".repeat(56), "Fix"))
                .get()
                .asString()
                .as("no room beside the project for any task")
                .doesNotContain("would do");

        // Too long for the project: cut to the room, as a name that is too long is.
        final String project = "p".repeat(40);
        final String refusal = ContainerName.refusal(project, "Fix the " + "x".repeat(20)).get();
        assertThat(refusal).as("cut at a word to the room").contains("'fix-the' would do");
        assertThat(ContainerName.refusal(project, "fix-the"))
                .as("the suggested name is one the same check takes")
                .isEmpty();

        assertThat(ContainerName.refusal("uc", "My Task"))
                .get()
                .asString()
                .as("a fitting one is still suggested")
                .contains("'my-task' would do");
    }

    @Test
    void aNameIsShortenedAtAWordWhereItCanAndNeverEndsInAHyphen() {
        assertThat(ContainerName.shortened("example-shop-api-templates", 22))
                .as("cut back to the last whole word")
                .isEqualTo("example-shop-api");
        assertThat(ContainerName.shortened("example-shop-api", 16))
                .as("a name that fits stays")
                .isEqualTo("example-shop-api");
        assertThat(ContainerName.shortened("example-shop-api", 13))
                .as("the hyphen right at the cut is dropped")
                .isEqualTo("example-shop");
        assertThat(ContainerName.shortened("x".repeat(40), 10))
                .as("a name with no word to cut at is cut where it must")
                .isEqualTo("x".repeat(10));
        assertThat(ContainerName.shortened("example-shop-api", 12))
                .as("a cut right before a hyphen keeps the whole word")
                .isEqualTo("example-shop");
        assertThat(ContainerName.shortened("abc", 0)).as("no room").isEmpty();
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
