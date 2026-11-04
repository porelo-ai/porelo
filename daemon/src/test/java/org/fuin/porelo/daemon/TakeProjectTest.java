package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.app.OrganizationProjects;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TakingRefusals;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link ProjectMethods#taken}: what a person's act on a carried project answers. */
class TakeProjectTest {

    private static final String RECORD =
            """
            {"project": "foo", "revision": "56420b3", "address": "git@github.com:acme/foo.git",
             "fetch_from": null,
             "signing_keys": [{"login": "alice", "role": "admin", "key": "ssh-ed25519 AAAA",
                               "valid_from": "2026-11-02", "valid_until": "2028-11-02",
                               "revoked_since": null}],
             "homeserver": null, "room": null}
            """;

    private final AtomicInteger takes = new AtomicInteger();

    private PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private OrganizationProjects carrying(final PoreloContext context, final Path dir)
            throws IOException {
        final Path files = dir.resolve("host/projects");
        Files.createDirectories(files);
        Files.writeString(files.resolve("foo.json"), RECORD);
        return new OrganizationProjects(context.paths().projects().organizationProjects(), files);
    }

    @Test
    void aCarriedProjectIsTakenAndAnswersItsFile(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final OrganizationProjects projects = carrying(context, dir);
        final Path records = context.paths().projects().organizationProjects();

        final Map<String, Object> answer =
                ProjectMethods.taken(
                        context,
                        "foo",
                        projects,
                        (machine, name) -> {
                            takes.incrementAndGet();
                            try {
                                Files.createDirectories(records.resolve("foo.git"));
                                Files.writeString(records.resolve("foo.json"), RECORD);
                                Files.writeString(
                                        records.resolve("foo.git/project.yml"), "project:\n");
                            } catch (IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                        });

        assertThat(takes).hasValue(1);
        assertThat(answer)
                .containsEntry("reason", "")
                .containsEntry("words", "")
                .containsEntry("file", records.resolve("foo.git/project.yml").toString());
    }

    @Test
    void aRefusedTakeSaysWhy(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);

        final Map<String, Object> answer =
                ProjectMethods.taken(
                        context,
                        "foo",
                        carrying(context, dir),
                        (machine, name) -> {
                            throw TakingRefusals.refusal("cannot-say", "not reached");
                        });

        assertThat(answer)
                .containsEntry("file", "")
                .containsEntry("reason", "cannot-say")
                .containsEntry("words", "not reached");
    }

    @Test
    void aNameTheHostDoesNotCarryIsNeverTaken(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);

        for (final String name : new String[] {"other", "../foo", ""}) {
            final Map<String, Object> answer =
                    ProjectMethods.taken(
                            context,
                            name,
                            carrying(context, dir),
                            (machine, taken) -> takes.incrementAndGet());
            assertThat(answer).containsEntry("file", "").containsEntry("reason", "not-carried");
        }
        assertThat(takes).hasValue(0);
    }
}
