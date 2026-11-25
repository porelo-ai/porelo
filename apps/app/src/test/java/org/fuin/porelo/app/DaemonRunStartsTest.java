package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A start made outside the daemon begins its run in the daemon, or is refused. */
class DaemonRunStartsTest {

    private static final String TASK = "porelo-uc_shop-2";

    private static final String URL = "https://review.stub.invalid/uc/shop/pull/7";

    private static final RunPlace AT = RunPlace.atPullRequest(RunPlace.REVIEWER, "shop-2", URL);

    @TempDir Path dir;

    private final List<String> asked = new CopyOnWriteArrayList<>();

    private VarlinkServer daemon(final VarlinkServer.Method beginRun) {
        final VarlinkServer server =
                new VarlinkServer(dir.resolve("d.sock"), TasksContract.INTERFACE);
        server.method("BeginRun", beginRun);
        Thread.ofVirtual().start(server);
        return server;
    }

    private DaemonRunStarts starts() {
        return new DaemonRunStarts(dir.resolve("d.sock"), LimitFactors.DEFAULT);
    }

    @Test
    void aRunAtAPullRequestIsBegunInTheDaemonForTheTasksContainer() throws Exception {
        try (VarlinkServer server =
                daemon(
                        (parameters, replies) -> {
                            asked.add(String.valueOf(parameters.get("task")));
                            replies.last(Map.of("run", 1, "head", "a".repeat(40)));
                        })) {
            starts().begin(TASK, AT);
        }

        assertThat(asked).as("asked once, for the container").containsExactly(TASK);
    }

    @Test
    void aDaemonsTypedRefusalRefusesTheStartByItsName() throws Exception {
        try (VarlinkServer server =
                daemon(
                        (parameters, replies) -> {
                            throw new VarlinkException(
                                    TasksContract.INTERFACE + ".NotTheTasksPush",
                                    Map.of("head", "a".repeat(40)));
                        })) {
            assertThatThrownBy(() -> starts().begin(TASK, AT))
                    .as("refused as the daemon refused it")
                    .isInstanceOf(TaskLaunch.RunStarts.Refused.class)
                    .hasMessageStartingWith("NotTheTasksPush");
        }
    }

    @Test
    void noDaemonRefusesTheStartAndSaysWhere() {
        assertThatThrownBy(() -> starts().begin(TASK, AT))
                .as("no run without the daemon that keeps it")
                .isInstanceOf(TaskLaunch.RunStarts.Refused.class)
                .hasMessageContaining("porelod does not answer");
    }

    @Test
    void aGatePushBeginsNothingAndAsksNoDaemon() {
        starts().begin(TASK, RunPlace.ownPush("uc", null, "shop-2"));

        assertThat(asked).as("nothing asked").isEmpty();
    }
}
