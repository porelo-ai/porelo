package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The host can be returned only when every measured main is known to be at central. */
final class ProjectGiveBack {
    private final ProjectFiles files;
    private final ProjectReadKeys keys;

    ProjectGiveBack(ProjectFiles files, ProjectRuntime runtime) {
        this.files = files;
        keys = new ProjectReadKeys(files, runtime);
    }

    Map<String, @Nullable Object> check(String account) throws IOException {
        final Path directory = files.etc.resolve("projects");
        files.directory(directory, true, true);
        final List<String> names = new ArrayList<>();
        final List<String> faults = new ArrayList<>();
        if (files.exists(directory)) {
            for (Path record : files.list(directory)) {
                final String file = record.getFileName().toString();
                if (!file.endsWith(".conf")) {
                    continue;
                }
                final String project = file.substring(0, file.length() - 5);
                if (!project.matches(ProjectRecords.PROJECT)) {
                    throw new HostRefusal(
                            65, "the projects directory holds an invalid project record");
                }
                names.add(project);
                files.trusted(record, true);
                for (Object value :
                        (List<?>)
                                java.util.Objects.requireNonNull(
                                        keys.centralState(account, project).get("repositories"),
                                        "Central state has repositories")) {
                    final Map<?, ?> row = (Map<?, ?>) value;
                    final String state =
                            (String)
                                    java.util.Objects.requireNonNull(
                                            row.get("state"), "Central row has a state");
                    if (state.equals("up-to-date")) {
                        continue;
                    }
                    final String said =
                            switch (state) {
                                case "ahead" -> "ahead by " + row.get("commits") + " commit(s)";
                                case "moved-on" -> "moved-on from central";
                                case "cannot-say" -> "cannot-say (" + row.get("why") + ")";
                                default -> state;
                            };
                    faults.add(project + "/" + row.get("repository") + " " + said);
                }
            }
        }
        if (!faults.isEmpty()) {
            throw new HostRefusal(
                    65,
                    "work on this host's forge is not at central: "
                            + String.join("; ", faults)
                            + "; bring it to central first, or give it back anyway to lose it");
        }
        return ProjectRecords.object("projects", names, "at_central", true);
    }
}
