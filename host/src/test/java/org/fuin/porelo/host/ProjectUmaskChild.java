package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.wire.Json;

/** Runs the project commands in a separate process with a real inherited umask. */
public final class ProjectUmaskChild {
    private ProjectUmaskChild() {}

    public static void main(String[] args) throws Exception {
        final Path root = Path.of(args[0]);
        Files.createDirectory(root);
        ProjectFixture.mode(root, 0700);
        try (ProjectFixture host = new ProjectFixture(root)) {
            host.realKeygen = true;
            host.umask = Integer.parseInt(args[1], 8);
            host.add();
            for (String name : java.util.List.of("projects", "people")) {
                assertThat(
                                (int) Files.getAttribute(host.files.etc.resolve(name), "unix:mode")
                                        & 0777)
                        .as("record directories have explicit private modes")
                        .isEqualTo(0700);
            }
            final var migrated =
                    host.runInput(
                            "migrate",
                            Json.write(
                                    Map.of(
                                            "address",
                                            ProjectFixture.ADDRESS,
                                            "token",
                                            ProjectFixture.CENTRAL_TOKEN)),
                            "alpha");
            assertThat(migrated.code()).as(migrated.err()).isZero();
            for (Path path :
                    java.util.List.of(
                            host.keyPath(), Path.of(host.keyPath() + ".pub"), host.manifest())) {
                assertThat((int) Files.getAttribute(path, "unix:mode") & 0777)
                        .as("generated keys and records have explicit private modes")
                        .isEqualTo(0600);
            }
            final var removed = host.run("remove", "alpha");
            assertThat(removed.code()).as(removed.err()).isZero();
        } catch (Throwable ex) {
            // HostPrograms deliberately keeps stderr separate; send the assertion to its bounded
            // stdout.
            ex.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
