package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.porelo.wire.Sidecar;

/** Ownership records written by a real start, without a container runtime. */
final class TaskIdentityFixture {
    private TaskIdentityFixture() {}

    static void record(PoreloPaths paths, String container, String project, String task, String id)
            throws IOException {
        final Path state = paths.tasks().taskRecord(container);
        Sidecar.recordId(state, id);
        java.nio.file.Files.setPosixFilePermissions(
                state, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        new Sidecar(
                        Sidecar.VERSION,
                        project,
                        "guarded",
                        "ruleset.nft",
                        "dns.conf",
                        "porelo",
                        paths.tasks().containerState(container).toString(),
                        task,
                        null,
                        null)
                .writeTo(state.resolve("sidecar.json"));
    }
}
