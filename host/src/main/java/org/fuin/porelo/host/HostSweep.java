package org.fuin.porelo.host;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Root's timer removes only tokens whose durable task certificate has ended. */
final class HostSweep {
    private static final String TASK_TOKEN = "porelo-task-";
    private final HostFiles files;
    private final ProjectRuntime runtime;
    private final Clock clock;

    HostSweep(HostFiles files) {
        this(files, new ProjectRuntime(files), Clock.systemUTC());
    }

    HostSweep(HostFiles files, ProjectRuntime runtime, Clock clock) {
        this.files = files;
        this.runtime = runtime;
        this.clock = clock;
    }

    Map<String, Object> run(Map<String, String> environment) {
        final var records = new HostRecords(files).read();
        if (environment.containsKey("SUDO_USER")) {
            throw new HostRefusal(65, "the sweep is root's alone, from its timer");
        }
        final List<String> errors = new HostRevocations(files, runtime).retry(records);
        final var now = clock.instant();
        final TreeMap<String, Set<String>> due = new TreeMap<>();
        // Each due token's name in the swept list: <project>/<task>, or the old layout's <task>.
        final Map<String, String> labels = new java.util.HashMap<>();
        for (var task : new HostTasks(files).read()) {
            final var record = records.get(task.login());
            if (!task.end().isAfter(now)
                    && record != null
                    && (!record.locked() || record.pending())) {
                due.computeIfAbsent(task.login(), key -> new TreeSet<>()).add(task.token());
                labels.put(
                        task.login() + "/" + task.token(),
                        task.project() == null ? task.name() : task.project() + "/" + task.name());
            }
        }
        if (due.isEmpty() || runtime.configuration(due.firstKey()) == null) {
            if (!errors.isEmpty()) {
                throw new HostRefusal(
                        69, "unfinished host revocation: " + String.join("; ", errors));
            }
            return Map.of("swept", List.of());
        }
        final List<String> swept = new ArrayList<>();
        try (var session = runtime.session(due.firstKey())) {
            for (var entry : due.entrySet()) {
                try {
                    for (String name : session.drop("porelo-" + entry.getKey(), entry.getValue())) {
                        swept.add(
                                entry.getKey()
                                        + "/"
                                        + labels.getOrDefault(
                                                entry.getKey() + "/" + name,
                                                name.substring(TASK_TOKEN.length())));
                    }
                } catch (HostRefusal error) {
                    errors.add(entry.getKey() + ": " + error.getMessage());
                }
            }
        }
        swept.sort(String::compareTo);
        if (!errors.isEmpty()) {
            throw new HostRefusal(
                    69,
                    "the sweep deleted "
                            + (swept.isEmpty() ? "nothing" : String.join(", ", swept))
                            + "; it failed for "
                            + String.join("; ", errors));
        }
        return Map.of("swept", swept);
    }
}
