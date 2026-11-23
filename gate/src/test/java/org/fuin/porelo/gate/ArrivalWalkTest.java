package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gate judges every commit a task's push brings, however many: no count refuses a push, and a
 * ref that lands while a push is walked hides none of its commits from the judges.
 */
class ArrivalWalkTest {

    private static final String ISSUE = "o/r#1";

    @TempDir Path dir;

    @Test
    void issueTrailersJudgeAPushOfMoreThanTenThousandCommits() throws Exception {
        final FakeMirror mirror = FakeMirror.line("c", 10_001);
        assertThat(trailers(mirror).judge(SignedArrivals.ZERO, FakeMirror.id("c10001")))
                .as("every commit carries the trailer")
                .isNull();
        mirror.untrailed.add(FakeMirror.id("c1"));
        assertThat(trailers(mirror).judge(SignedArrivals.ZERO, FakeMirror.id("c10001")))
                .as("the oldest commit of the push is judged too")
                .contains(FakeMirror.id("c1"));
    }

    @Test
    void signedArrivalsJudgeAPushOfMoreThanTenThousandCommits() throws Exception {
        final FakeMirror mirror = FakeMirror.line("c", 10_001);
        mirror.unsigned.add(FakeMirror.id("c10001"));
        assertThat(signed(mirror).judge(SignedArrivals.ZERO, FakeMirror.id("c10001")))
                .as("refused for what the commit is, never for how many the push brings")
                .startsWith("commit " + FakeMirror.id("c10001") + " is not signed");
    }

    @Test
    void aRefLandingBetweenTwoWindowsHidesNoUnsignedCommit() throws Exception {
        final FakeMirror mirror = interleaved();
        mirror.unsigned.add(FakeMirror.id("m11"));
        assertThat(signed(mirror).walkingBy(4).judge(SignedArrivals.ZERO, FakeMirror.id("tip")))
                .as("the commit a shifted window would skip is judged")
                .contains(FakeMirror.id("m11"));
    }

    @Test
    void aRefLandingBetweenTwoWindowsHidesNoCommitWithoutTheTrailer() throws Exception {
        final FakeMirror mirror = interleaved();
        mirror.untrailed.add(FakeMirror.id("m11"));
        assertThat(trailers(mirror).walkingBy(4).judge(SignedArrivals.ZERO, FakeMirror.id("tip")))
                .as("the commit a shifted window would skip is judged")
                .contains(FakeMirror.id("m11"));
    }

    @Test
    void anExactlyFullWindowEndsTheWalkAndWhatTheRefHeldIsLeftOut() throws Exception {
        final FakeMirror mirror = FakeMirror.line("c", 8);
        mirror.refs.put("refs/heads/main", "c2");
        for (int index = 1; index <= 4; index++) mirror.unsigned.add(FakeMirror.id("c" + index));
        assertThat(signed(mirror).walkingBy(4).judge(FakeMirror.id("c4"), FakeMirror.id("c8")))
                .isNull();
        assertThat(mirror.read)
                .as("each new commit once, nothing below what the ref held")
                .containsExactly(
                        FakeMirror.id("c8"),
                        FakeMirror.id("c7"),
                        FakeMirror.id("c6"),
                        FakeMirror.id("c5"));
    }

    /**
     * A push whose side line is newer than its main line, so that the side line fills the first
     * window: when a ref then lands on it, a walk that read the refs anew would shift its next
     * window past the main line's newest commits.
     */
    private static FakeMirror interleaved() {
        final FakeMirror mirror = FakeMirror.line("m", 12);
        mirror.commit("s1", 101, java.util.List.of());
        mirror.commit("s2", 102, java.util.List.of("s1"));
        mirror.commit("s3", 103, java.util.List.of("s2"));
        mirror.commit("tip", 1000, java.util.List.of("m12", "s3"));
        mirror.afterFirstWindow = () -> mirror.refs.put("refs/heads/earlier", "s3");
        return mirror;
    }

    private IssueTrailers trailers(FakeMirror mirror) {
        return new IssueTrailers(dir.resolve("mirror.git"), ISSUE, mirror);
    }

    private SignedArrivals signed(FakeMirror mirror) throws Exception {
        final Path allowed = dir.resolve("allowed");
        Files.writeString(allowed, "");
        // ssh-keygen's stand-in accepts every signature, so only what is walked decides.
        return new SignedArrivals(
                dir.resolve("mirror.git"),
                "task:demo:fix",
                allowed,
                mirror,
                Clock.systemUTC(),
                "true");
    }
}
