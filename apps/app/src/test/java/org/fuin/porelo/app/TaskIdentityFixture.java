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

    /**
     * The records of a task whose container was started: a start writes its recorded id and its
     * sidecar into the runtime directory too, which a stop saves over the durable copies.
     */
    static void running(PoreloPaths paths, String container, String project, String task, String id)
            throws IOException {
        record(paths, container, project, task, id);
        final Path durable = paths.tasks().taskRecord(container);
        final Path runtime = paths.tasks().containerState(container);
        java.nio.file.Files.createDirectories(runtime);
        Sidecar.recordId(runtime, id);
        java.nio.file.Files.copy(durable.resolve("sidecar.json"), runtime.resolve("sidecar.json"));
    }

    /**
     * A name whose ownership cannot be verified: its reservation names the task and its id, and its
     * task-record folder survives while its identity record is gone, as a late wake leaves it.
     */
    static void unverifiable(
            PoreloContext context, String container, String project, String task, String id)
            throws IOException {
        try (var claim = new TaskReservations(context).reserve(project, task, container, "first")) {
            running(context.paths(), container, project, task, id);
            claim.created(id);
        }
        for (final Path directory :
                java.util.List.of(
                        context.paths().tasks().containerState(container),
                        context.paths().tasks().taskRecord(container))) {
            try (var walk = java.nio.file.Files.walk(directory)) {
                for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.delete(path);
                }
            }
        }
        java.nio.file.Files.createDirectories(context.paths().tasks().taskRecord(container));
        java.nio.file.Files.createFile(
                context.paths().tasks().taskRecord(container).resolve(".wake.lock"));
    }
}
