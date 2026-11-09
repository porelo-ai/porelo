package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Git fixtures confined to JUnit's temporary trees; never the checkout or the source Python
 * repository.
 */
final class ProjectTestGit {
    private ProjectTestGit() {}

    static String git(Path path, String... arguments) throws IOException {
        final var argv = new ArrayList<>(List.of("/usr/bin/git", "-C", path.toString()));
        argv.addAll(List.of(arguments));
        final var result =
                new HostPrograms(Duration.ofSeconds(30), 1024 * 1024, Path::of)
                        .run(
                                argv,
                                new byte[0],
                                Map.of(
                                        "PATH",
                                        "/usr/bin:/bin",
                                        "LC_ALL",
                                        "C",
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1",
                                        "GIT_AUTHOR_NAME",
                                        "test",
                                        "GIT_AUTHOR_EMAIL",
                                        "test@example.org",
                                        "GIT_COMMITTER_NAME",
                                        "test",
                                        "GIT_COMMITTER_EMAIL",
                                        "test@example.org"));
        if (result.exitCode() != 0) {
            throw new AssertionError("Fixture Git failed: " + List.of(arguments));
        }
        return result.output().strip();
    }
}
