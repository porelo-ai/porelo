package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests that a run's log is a file of its own, never the last run's emptied. */
class RunLogTest {

    @TempDir Path dir;

    @Test
    void aNewRunsLogIsAnotherFileThatIsEmpty() throws Exception {
        // Emptied in place, the last run's log kept its file, and a client paging it was shown the
        // new run's lines at its old positions.
        final Path log = Files.writeString(dir.resolve("task.log"), "last run\n");
        final Object before = key(log);

        RunLog.begin(log);

        assertThat(log).as("the log is there, empty").isEmptyFile();
        assertThat(key(log)).as("another file").isNotEqualTo(before);
        assertThat(dir.resolve("task.log" + RunLog.NEXT))
                .as("nothing left beside it")
                .doesNotExist();
    }

    @Test
    void aHelpersLogIsBegunAsAnotherFileItsOutputIsAppendedTo() throws Exception {
        // A helper's output sent to its log's name emptied the last run's file in place, and a
        // client paging it kept its position, which then named the new run's lines.
        final Path log = Files.writeString(dir.resolve("clearance.log"), "last run\n");
        final Object before = key(log);

        final ProcessBuilder.Redirect redirect = RunLog.into(log);

        assertThat(log).as("the log is there, empty").isEmptyFile();
        assertThat(key(log)).as("another file").isNotEqualTo(before);
        assertThat(redirect.type())
                .as("written onto the new file, never emptying it again")
                .isEqualTo(ProcessBuilder.Redirect.Type.APPEND);
        assertThat(redirect.file()).as("the log itself").isEqualTo(log.toFile());
    }

    @Test
    void aLinkInTheLogsPlaceIsReplacedAndItsTargetLeftAlone() throws Exception {
        final Path target = Files.writeString(dir.resolve("elsewhere"), "keep\n");
        final Path log = Files.createSymbolicLink(dir.resolve("task.log"), target);
        Files.createSymbolicLink(dir.resolve("task.log" + RunLog.NEXT), target);

        RunLog.begin(log);

        assertThat(Files.isSymbolicLink(log)).as("no link any more").isFalse();
        assertThat(log).isEmptyFile();
        assertThat(target).as("what the link named is untouched").hasContent("keep");
    }

    private static Object key(final Path file) throws Exception {
        return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .fileKey();
    }
}
