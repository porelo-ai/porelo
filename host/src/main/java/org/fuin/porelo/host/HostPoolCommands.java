package org.fuin.porelo.host;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** The pool's root service, VM import, and the run account's give-back. */
final class HostPoolCommands {
    private HostPoolCommands() {}

    static CommandLine commandLine(Map<String, String> environment) {
        return new CommandLine(new PoreloHost.Group())
                .addSubcommand("revert", new Revert(environment))
                .addSubcommand("give-back", new GiveBack(environment))
                .addSubcommand("make", new Make(environment))
                .addSubcommand("address", new Address(environment))
                .addSubcommand("delete", new Delete(environment));
    }

    private static void rootUnit(Map<String, String> environment) {
        if (environment.containsKey("SUDO_USER"))
            throw new HostRefusal(
                    65,
                    "the pool's service and VM creation are root's alone and never run through sudo");
    }

    @Command(
            name = "revert",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 2,
            description = "Revert stopped pool VMs; --one settles just one VM for up.")
    static final class Revert implements Callable<Integer> {
        private final Map<String, String> environment;

        @CommandLine.Option(names = "--one")
        private @Nullable String one;

        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "<list>")
        private @Nullable Path list;

        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        Revert(Map<String, String> environment) {
            this.environment = environment;
        }

        @Override
        public Integer call() throws Exception {
            rootUnit(environment);
            final HostPool pool =
                    new HostPool(spec.commandLine().getOut(), spec.commandLine().getErr());
            if (one != null && list == null) return pool.one(one);
            if (one == null && list != null) return pool.service(list);
            throw new HostRefusal(
                    2, "usage: porelo-pool-revert <file with one VM name per line> | --one <name>");
        }
    }

    @Command(
            name = "give-back",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 2,
            description = "Mark the sudo run account resetting and schedule poweroff.")
    static final class GiveBack implements Callable<Integer> {
        private final Map<String, String> environment;
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        GiveBack(Map<String, String> environment) {
            this.environment = environment;
        }

        @Override
        public Integer call() throws Exception {
            return new HostPool(spec.commandLine().getOut(), spec.commandLine().getErr())
                    .giveBack(environment);
        }
    }

    @Command(
            name = "make",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 2,
            description = "Import a pool VM from its prepared seed and backing volume.")
    static final class Make implements Callable<Integer> {
        private final Map<String, String> environment;

        @CommandLine.Parameters(index = "0")
        private String name;

        @CommandLine.Parameters(index = "1")
        private String volume;

        @CommandLine.Parameters(index = "2")
        private Path seed;

        @CommandLine.Parameters(index = "3")
        private int memory;

        @CommandLine.Parameters(index = "4")
        private int vcpus;

        @CommandLine.Parameters(index = "5")
        private String osinfo;

        Make(Map<String, String> environment) {
            this.environment = environment;
        }

        @Override
        public Integer call() throws Exception {
            rootUnit(environment);
            new PoolMachines().make(name, volume, seed, memory, vcpus, osinfo);
            return 0;
        }
    }

    @Command(
            name = "address",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 1,
            description = "Wait for a pool VM's IPv4 lease, for at most the given seconds.")
    static final class Address implements Callable<Integer> {
        private final Map<String, String> environment;

        @CommandLine.Parameters(index = "0")
        private String name;

        @CommandLine.Parameters(index = "1", defaultValue = "300")
        private long seconds;

        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        Address(Map<String, String> environment) {
            this.environment = environment;
        }

        @Override
        public Integer call() throws Exception {
            rootUnit(environment);
            spec.commandLine().getOut().println(new PoolMachines().address(name, seconds));
            return 0;
        }
    }

    @Command(
            name = "delete",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 1,
            description = "Delete a pool VM and verify its domain and volumes are gone.")
    static final class Delete implements Callable<Integer> {
        private final Map<String, String> environment;

        @CommandLine.Parameters(index = "0")
        private String name;

        Delete(Map<String, String> environment) {
            this.environment = environment;
        }

        @Override
        public Integer call() throws Exception {
            rootUnit(environment);
            new PoolMachines().delete(name);
            return 0;
        }
    }
}
