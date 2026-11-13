package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.jspecify.annotations.Nullable;

/** The judged membership of a project's room, read from its transport. */
public record RoomMembers(String room, List<Member> members) {

    /** Keeps a snapshot from being changed after it was judged. */
    public RoomMembers {
        members = List.copyOf(members);
    }

    /** A room member; only a task carries a container name. */
    public record Member(
            String id, String shown, String state, String kind, @Nullable String task) {
        /** Returns the fields a client is given. */
        public Map<String, Object> asMap() {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("shown", shown);
            row.put("state", state);
            row.put("kind", kind);
            if (task != null) {
                row.put("task", task);
            }
            return row;
        }
    }

    /** Why a live membership query could not answer. */
    public enum Failure {
        NO_TRANSPORT,
        NO_ROOM,
        UNREACHABLE,
        REFUSED
    }

    /** A named refusal, with the transport's exit code when it ran. */
    public static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        private final Failure failure;
        private final String transport;
        private final int code;

        Refused(Failure failure, String transport, String message, int code) {
            super(message);
            this.failure = failure;
            this.transport = transport;
            this.code = code;
        }

        /** Returns the reason a client can act on. */
        public Failure failure() {
            return failure;
        }

        /** Returns the transport, or empty when no conversation is declared. */
        public String transport() {
            return transport;
        }

        /** Returns the transport's code, or zero when it did not run. */
        public int code() {
            return code;
        }
    }

    /** Reads the live room without setting it up or changing membership. */
    public static RoomMembers read(PoreloContext context, Project project) throws Refused {
        return read(context, project, org.fuin.porelo.wire.SystemProgram::find);
    }

    /** Replaces only program resolution for tests whose runner never starts namespace tools. */
    static RoomMembers read(
            final PoreloContext context,
            final Project project,
            final java.util.function.Function<String, java.nio.file.Path> programs)
            throws Refused {
        if (project.mail().conversations().isEmpty()) {
            throw new Refused(Failure.NO_ROOM, "", "the project has no room", 0);
        }
        final String scheme = project.mail().conversations().iterator().next();
        final java.util.Map<String, Object> settings;
        try {
            settings = ProjectMembers.settings(context, project, scheme);
        } catch (ProjectMembers.NoHomeserver ex) {
            throw new Refused(
                    Failure.NO_ROOM,
                    scheme,
                    "the project names no homeserver, so it has no room",
                    0);
        } catch (ProjectMembers.Withheld ex) {
            throw new Refused(Failure.REFUSED, scheme, String.valueOf(ex.getMessage()), 78);
        }
        final TransportDirectory transports = context.paths().messaging().transportDirectory();
        final java.nio.file.Path adapter = transports.find(scheme);
        if (adapter == null
                || !TransportDescription.of(context.runner(), adapter).offers("members")) {
            throw new Refused(
                    Failure.NO_TRANSPORT,
                    scheme,
                    "no installed " + scheme + " transport offers members",
                    0);
        }
        try {
            final RoomMembers result =
                    new TransportLifecycle(
                                    context, transports, OrganizationProjects.HOST_FILES, programs)
                            .members(
                                    scheme,
                                    project.name(),
                                    settings,
                                    project.securityClass() == SecurityClass.OFFLINE);
            if (result.room().isEmpty()) {
                throw new Refused(Failure.NO_ROOM, scheme, "the project has no room yet", 0);
            }
            return result;
        } catch (TransportLifecycle.Refused ex) {
            throw new Refused(
                    ex.code() == 75 ? Failure.UNREACHABLE : Failure.REFUSED,
                    scheme,
                    String.valueOf(ex.getMessage()),
                    ex.code());
        }
    }

    /** Returns the reply fields, without transport-private values. */
    public Map<String, Object> asMap() {
        return Map.of("room", room, "members", members.stream().map(Member::asMap).toList());
    }
}
