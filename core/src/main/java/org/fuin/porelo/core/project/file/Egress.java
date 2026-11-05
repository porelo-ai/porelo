package org.fuin.porelo.core.project.file;

import java.util.List;

/**
 * The {@code egress} section: what a task may reach. The project's alone.
 *
 * @param sets Curated sets by name ({@code sets}).
 * @param domains Other names, on ports 80 and 443 ({@code domains}).
 * @param hostServices Services on this host, on TCP 443 ({@code host_services}).
 * @param refused Names that must not resolve ({@code refused}).
 */
public record Egress(
        List<String> sets, List<String> domains, List<String> hostServices, List<String> refused) {

    /**
     * Constructor.
     *
     * @param sets Curated sets.
     * @param domains Other names.
     * @param hostServices Services on this host.
     * @param refused Names that must not resolve.
     */
    public Egress {
        sets = sets == null ? List.of() : List.copyOf(sets);
        domains = domains == null ? List.of() : List.copyOf(domains);
        hostServices = hostServices == null ? List.of() : List.copyOf(hostServices);
        refused = refused == null ? List.of() : List.copyOf(refused);
    }
}
