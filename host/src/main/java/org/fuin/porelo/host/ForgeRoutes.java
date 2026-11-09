package org.fuin.porelo.host;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Forgejo's segment decoding and the gate's credential and browser boundaries. */
final class ForgeRoutes {
    static final Pattern MINTING =
            Pattern.compile(
                    "/(login/oauth|user/recover_account|user/settings|admin/users|admin/actions/runners|api/v1/users/[^/]+/tokens"
                            + "|api/v1/(orgs/[^/]+|repos/[^/]+/[^/]+|user|admin)/actions/runners|api/v1/admin/runners"
                            + "|(org/[^/]+|[^/]+/[^/]+)/settings/actions/runners)(/.*)?",
                    Pattern.DOTALL);
    static final Pattern TOKEN_ONLY =
            Pattern.compile(
                    "/(v2|api/packages)(/.*)?|/(?!user/)[^/]+/[^/]+/(info/refs|git-upload-pack|git-receive-pack|info/lfs(/.*)?|head"
                            + "|objects/(info|pack|[0-9a-f]{2})(/.*)?)",
                    Pattern.DOTALL);
    static final Pattern TOKEN_EXCHANGE =
            Pattern.compile(
                    "/v2/token(/.*)?|/api/packages/[^/]+/conan/v[12]/users/authenticate",
                    Pattern.DOTALL);

    private ForgeRoutes() {}

    static String path(String target) {
        // urlsplit ignores a query and fragment before the router sees path segments.
        int end = target.length();
        for (char delimiter : new char[] {'?', '#'}) {
            final int found = target.indexOf(delimiter);
            if (found >= 0) end = Math.min(end, found);
        }
        return target.substring(0, end);
    }

    private static List<String> parts(String target) {
        final String[] raw = path(target).split("/", -1);
        final List<String> parts = new ArrayList<>();
        for (int index = 1; index < raw.length; index++) parts.add(decode(raw[index]));
        return parts;
    }

    private static String decode(String segment) {
        final StringBuilder result = new StringBuilder();
        for (int index = 0; index < segment.length(); ) {
            if (segment.charAt(index) != '%') {
                result.append(segment.charAt(index++));
                continue;
            }
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index + 2 < segment.length()
                    && segment.charAt(index) == '%'
                    && Character.digit(segment.charAt(index + 1), 16) >= 0
                    && Character.digit(segment.charAt(index + 2), 16) >= 0) {
                bytes.write(Integer.parseInt(segment.substring(index + 1, index + 3), 16));
                index += 3;
            }
            if (bytes.size() == 0) result.append(segment.charAt(index++));
            else result.append(bytes.toString(StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    static boolean unsafe(String target) {
        final String raw = path(target);
        if (Pattern.compile("%(?![0-9A-Fa-f]{2})").matcher(raw).find()
                || (Pattern.compile("%2[fF]").matcher(raw).find()
                        && Pattern.compile("[^A-Za-z0-9\\-._~!$&'()*+,;=:@/%]")
                                .matcher(raw)
                                .find())) return true;
        final List<String> parts = parts(target);
        for (int index = 0; index < parts.size(); index++) {
            final String part = parts.get(index);
            if ((part.isEmpty() && index < parts.size() - 1)
                    || Pattern.compile("[\\x00-\\x1f\\x7f]").matcher(part).find()) return true;
            final String[] pieces = part.split("/", -1);
            for (String piece : pieces) {
                if (piece.equals(".")
                        || piece.equals("..")
                        || (pieces.length > 1 && piece.isEmpty())) return true;
            }
        }
        return false;
    }

    static String route(String target) {
        final String decoded =
                String.join(
                        "/", parts(target).stream().map(part -> part.replace("/", "%2f")).toList());
        return "/" + decoded.replaceFirst("/+$", "").toLowerCase(Locale.ROOT);
    }

    static boolean runner(String target) {
        final String path = path(target);
        return target.startsWith("/api/actions/")
                && !path.contains("%")
                && !unsafe(target)
                && !path.endsWith("/");
    }

    static boolean blocked(String target, String method, String mode) {
        final String path = route(target);
        return MINTING.matcher(path).matches()
                || TOKEN_EXCHANGE.matcher(path).matches()
                || Set.of("/user/forgot_password", "/user/reset_password", "/user/change_password")
                        .contains(path)
                || (!Set.of("GET", "HEAD", "OPTIONS").contains(method)
                        && (path.equals("/api/v1/user")
                                || path.equals("/api/v1/admin/users")
                                || path.startsWith("/api/v1/admin/users/")))
                || (!mode.equals("runner")
                        && (path.equals("/api/actions") || path.startsWith("/api/actions/")));
    }

    static Set<String> hosts(String configuration) {
        final Map<String, String> values = new HashMap<>();
        final String[] lines = configuration.split("\n", -1);
        final int count =
                lines.length > 0 && lines[lines.length - 1].isEmpty()
                        ? lines.length - 1
                        : lines.length;
        for (int index = 0; index < count; index++) {
            final int equals = lines[index].indexOf('=');
            if (equals < 1) return Set.of();
            final String key = lines[index].substring(0, equals);
            final String value = lines[index].substring(equals + 1);
            if (!key.matches("[a-z_]+")
                    || Pattern.compile("\\s", Pattern.UNICODE_CHARACTER_CLASS).matcher(value).find()
                    || values.putIfAbsent(key, value) != null) return Set.of();
        }
        final String name = values.getOrDefault("name", "");
        final String port = values.getOrDefault("port", "");
        if (!name.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") || !port.matches("[1-9][0-9]{0,4}"))
            return Set.of();
        return Set.copyOf(List.of(name + ":" + port, "127.0.0.1:" + port));
    }

    static boolean foreign(GateHttp.Headers headers, String method) {
        if (method.equals("GET") || method.equals("HEAD")) return false;
        final var hosts = headers.all("Host");
        final var origins = headers.all("Origin");
        final var sites = headers.all("Sec-Fetch-Site");
        return origins.size() > 1
                || sites.size() > 1
                || (!origins.isEmpty()
                        && (hosts.size() != 1
                                || !origins.getFirst()
                                        .equalsIgnoreCase("http://" + hosts.getFirst())))
                || (!sites.isEmpty()
                        && !Set.of("same-origin", "none")
                                .contains(sites.getFirst().strip().toLowerCase(Locale.ROOT)));
    }

    static byte[] guidance(
            GateHttp.Headers headers, String method, String target, Set<String> hosts) {
        if (hosts.isEmpty()
                || foreign(headers, method)
                || unsafe(target)
                || blocked(target, method, "web")
                || !target.startsWith("/")
                || target.startsWith("//")
                || (headers.all("Authorization").isEmpty()
                        && TOKEN_ONLY.matcher(route(target)).matches()
                        && headers.browser())) return new byte[0];
        return ("Open this forge at "
                        + String.join(
                                " or ",
                                hosts.stream()
                                        .sorted()
                                        .map(host -> "http://" + host + "/")
                                        .toList())
                        + ".\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
