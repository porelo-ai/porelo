package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;

/** Lists the ecosystem plugins this machine can use. */
@Command(
        name = "ecosystems",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Lists installed ecosystem plugins.")
public class EcosystemsCommand extends OrgReading {
    @Override
    Map<String, @Nullable Object> answer(PoreloContext machine) {
        return Map.of(
                "ecosystems",
                InstalledEcosystems.all(machine.paths().ecosystemDirectories()).stream()
                        .map(
                                each -> {
                                    final Map<String, @Nullable Object> row = new LinkedHashMap<>();
                                    row.put("name", each.name());
                                    row.put("path", each.executable().toString());
                                    row.put("refusal", each.refusal());
                                    return row;
                                })
                        .toList());
    }

    @Override
    void text(Map<String, @Nullable Object> answer) {
        if (answer.get("ecosystems") instanceof java.util.List<?> plugins) {
            if (plugins.isEmpty()) line("No ecosystem plugins installed.");
            for (final Object entry : plugins) {
                if (entry instanceof Map<?, ?> plugin) {
                    line(plugin.get("name") + "  " + plugin.get("path"));
                    if (plugin.get("refusal") != null) line("  refused: " + plugin.get("refusal"));
                }
            }
        }
    }
}
