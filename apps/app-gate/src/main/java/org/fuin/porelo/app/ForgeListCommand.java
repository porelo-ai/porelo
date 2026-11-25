package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.wire.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Lists the forge instances this machine knows and the forge adapters installed. */
@Command(
        name = "list",
        mixinStandardHelpOptions = true,
        description = "Lists the forge instances this machine knows and the adapters installed.")
public class ForgeListCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = "--json", description = "Answers as the daemon's Forges does.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final ForgeInstanceFile forges = ForgeInstanceFile.of(context);
        final List<ForgeInstance> all;
        try {
            all = forges.all();
        } catch (ForgeFileUnreadable ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
        final List<String> adapters = ForgeAdapters.of(context).names();
        if (json) {
            final List<Map<String, Object>> listed = new ArrayList<>();
            for (final ForgeInstance instance : all) {
                final Map<String, Object> forge = new LinkedHashMap<>();
                forge.put("host", instance.host());
                forge.put("adapter", instance.adapter() == null ? "" : instance.adapter());
                if (instance.unreadable() == null) {
                    forge.put("comments", instance.reading().name());
                } else {
                    forge.put("unreadable", instance.unreadable());
                }
                listed.add(forge);
            }
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("forges", listed);
            answer.put("adapters", adapters);
            out.println(Json.write(answer));
            out.flush();
            return 0;
        }
        if (all.isEmpty()) {
            out.println("no forge is known; 'porelo forge add <host> --adapter <name>' adds one");
        }
        for (final ForgeInstance instance : all) {
            out.println(
                    instance.unreadable() == null
                            ? String.format(
                                    Locale.ROOT,
                                    "%-30s %-12s comments %s",
                                    instance.host(),
                                    instance.adapter(),
                                    instance.reading().name().toLowerCase(Locale.ROOT))
                            : String.format(
                                    Locale.ROOT,
                                    "%-30s unreadable: %s",
                                    instance.host(),
                                    instance.unreadable()));
        }
        forges.loosened().ifPresent(why -> out.println("note      " + why));
        out.println(
                "adapters  "
                        + (adapters.isEmpty() ? "none installed" : String.join(", ", adapters)));
        out.flush();
        return 0;
    }
}
