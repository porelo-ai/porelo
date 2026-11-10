package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.wire.Sidecar;

/** The id and sidecar a legacy start writes, independent of a task's spelling. */
final class LegacyTaskFixture {
    private LegacyTaskFixture() {}

    static void record(PoreloContext context, String container, String project, String id)
            throws IOException {
        final Path runtime = context.paths().tasks().containerState(container);
        final Path durable = context.paths().tasks().taskRecord(container);
        Sidecar.recordId(durable, id);
        // TaskState.save copies the sidecar unchanged: its paths still name the runtime directory.
        new Sidecar(
                        Sidecar.VERSION,
                        project,
                        "guarded",
                        runtime.resolve("ruleset.nft").toString(),
                        runtime.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        runtime.toString())
                .writeTo(durable.resolve("sidecar.json"));
    }

    static String listed(String rows, String id) {
        return rows.lines()
                .map(
                        row -> {
                            final String[] fields = row.split("\t", -1);
                            if (!ContainerName.isTask(fields[0]) || fields.length > 8) {
                                return row;
                            }
                            final String[] complete = Arrays.copyOf(fields, 9);
                            Arrays.fill(complete, fields.length, 9, "");
                            complete[8] = id;
                            return String.join("\t", complete);
                        })
                .collect(Collectors.joining("\n"));
    }
}
