package org.fuin.porelo.packagecheck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Holds what the deb carries of Porelo's two AppArmor profiles to this tree, and the maintainer
 * scripts to loading and unloading them.
 *
 * <p>A profile is a security rule set an administrator reads before loading it, so every rule says
 * what it is for: a rule no comment stands over is refused. The profiles are conffiles, so an
 * update never overwrites a change made to them; postinst loads them and postrm unloads them. A
 * package that lost either would leave a task as it was before, with nothing saying so but {@code
 * porelo doctor}.
 */
final class AppArmorProfiles {

    /** The two profiles, by file name, which is also the profile's name. */
    static final List<String> NAMES = List.of("porelo", "porelo-task");

    /** Where this tree keeps them. */
    static final String TREE = "apparmor";

    /** Where the deb installs them. */
    static final String INSTALLED = "/etc/apparmor.d/";

    /** What postinst runs: both, replaced where loaded, the cache written for the boot. */
    static final String LOAD =
            "apparmor_parser -r -W /etc/apparmor.d/porelo /etc/apparmor.d/porelo-task";

    /** The kernel's interface postrm unloads them through, by name. */
    static final String UNLOAD = "/sys/kernel/security/apparmor/.remove";

    private static final String TASK_DECLARATION =
            "profile porelo-task flags=(attach_disconnected, mediate_deleted) {";

    /** Network grants also grant unix access unless restricted to the listed families. */
    private static final Pattern SOCKET_RULE = Pattern.compile("\\b(?:unix|network|all)\\b");

    private static final List<String> TASK_SOCKET_RULES =
            List.of(
                    "network inet,",
                    "network inet6,",
                    "network netlink,",
                    "unix (create, bind, listen, accept, getattr, setattr, getopt, setopt, shutdown,"
                            + " read, write, send, receive),",
                    "unix (connect) peer=(label=porelo),",
                    "unix (connect) peer=(label=porelo-task),");

    /** An exec mode may be combined with file permissions, and capitals request a clean exec. */
    private static final Pattern TRANSITION =
            Pattern.compile("\\bchange_profile\\b|\\b[rmwalk]*[pPcCuU][iIuU]?[xX][rmwalk]*\\b");

    private AppArmorProfiles() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns every rule of a profile that no comment stands over.
     *
     * <p>A comment stands over the lines under it, up to the next blank line, so one comment may
     * explain a group of rules. A closing brace is no rule.
     *
     * @param profile the profile's text
     * @return the rules, trimmed, in their order
     */
    static List<String> uncommented(String profile) {
        final List<String> rules = new ArrayList<>();
        boolean commented = false;
        for (final String line : profile.lines().toList()) {
            final String text = line.strip();
            if (text.isEmpty()) {
                commented = false;
            } else if (text.startsWith("#")) {
                commented = true;
            } else if (!"}".equals(text) && !commented) {
                rules.add(text);
            }
        }
        return rules;
    }

    /**
     * Holds the profiles the deb carries to this tree's, and each to a comment over every rule.
     *
     * @param packaged each profile's text by the path the deb installs it at; absent if it has none
     * @param root the repository's root
     * @param report where the verdicts go
     */
    static void profiles(Map<String, String> packaged, Path root, Report report) {
        for (final String name : NAMES) {
            final String path = INSTALLED + name;
            final String held = packaged.get(path);
            if (held == null) {
                report.fail("the deb holds no " + path);
                continue;
            }
            report.check(
                    read(root.resolve(TREE).resolve(name)).equals(held),
                    "the deb holds " + path + " as " + TREE + "/" + name + " is",
                    "the deb's " + path + " is not " + TREE + "/" + name);
            final List<String> bare = uncommented(held);
            if (bare.isEmpty()) {
                report.pass("every rule of " + path + " says what it is for");
            }
            bare.forEach(rule -> report.fail(path + " has a rule with no comment: " + rule));
            if ("porelo-task".equals(name)) {
                task(held, report);
            }
        }
    }

    /**
     * Holds the shipped task profile's ABI, socket grants and enforcing declaration, and refuses
     * rules that let a task leave its label. This pins the shipped rule forms, not arbitrary
     * AppArmor syntax or an administrator's local additions.
     *
     * @param profile the task profile's text
     * @param report where the verdicts go
     */
    static void task(String profile, Report report) {
        final List<String> rules =
                profile.lines()
                        .map(line -> line.split("#", 2)[0].strip().replaceAll("\\s+", " "))
                        .filter(line -> !line.isEmpty())
                        .toList();
        final List<String> abi = rules.stream().filter(line -> line.startsWith("abi ")).toList();
        report.check(
                abi.equals(List.of("abi <abi/5.0>,")),
                "porelo-task uses abi <abi/5.0>,",
                "porelo-task needs abi <abi/5.0>,; found " + abi);
        final List<String> declarations =
                rules.stream().filter(line -> line.startsWith("profile ")).toList();
        report.check(
                declarations.equals(List.of(TASK_DECLARATION)),
                "porelo-task enforces its rules",
                "porelo-task declaration must be " + TASK_DECLARATION + "; found " + declarations);
        final List<String> sockets =
                rules.stream().filter(line -> SOCKET_RULE.matcher(line).find()).toList();
        report.check(
                sockets.equals(TASK_SOCKET_RULES),
                "porelo-task connects only to porelo and porelo-task peers",
                "porelo-task socket rules must be exactly "
                        + TASK_SOCKET_RULES
                        + "; found "
                        + sockets);
        final List<String> transitions =
                rules.stream().filter(line -> TRANSITION.matcher(line).find()).toList();
        if (transitions.isEmpty()) {
            report.pass("porelo-task allows no profile transition");
        }
        transitions.forEach(
                rule -> report.fail("porelo-task allows a profile transition: " + rule));
    }

    /**
     * Holds the deb's maintainer scripts to loading and unloading both profiles, and its conffiles
     * to naming them.
     *
     * @param postinst the deb's postinst, or {@code null} if it has none
     * @param postrm the deb's postrm, or {@code null} if it has none
     * @param conffiles the deb's conffiles list, or {@code null} if it has none
     * @param report where the verdicts go
     */
    static void scripts(
            @Nullable String postinst,
            @Nullable String postrm,
            @Nullable String conffiles,
            Report report) {
        if (postinst == null) {
            report.fail("the deb has no postinst");
        } else {
            report.check(
                    postinst.contains(LOAD),
                    "the deb's postinst loads both AppArmor profiles",
                    "the deb's postinst loads no AppArmor profile");
        }
        if (postrm == null) {
            report.fail("the deb has no postrm");
        } else {
            for (final String name : NAMES) {
                report.check(
                        unloads(postrm, name),
                        "the deb's postrm unloads " + name,
                        "the deb's postrm does not unload " + name);
            }
        }
        final List<String> listed =
                conffiles == null ? List.of() : conffiles.lines().map(String::strip).toList();
        for (final String name : NAMES) {
            report.check(
                    listed.contains(INSTALLED + name),
                    INSTALLED + name + " is a conffile of the deb",
                    INSTALLED + name + " is no conffile of the deb");
        }
    }

    /**
     * Says whether a postrm writes a profile's name to the kernel's interface: the script names it
     * in the loop over the profiles it unloads.
     *
     * @param postrm the script
     * @param name the profile
     * @return whether it is unloaded
     */
    static boolean unloads(String postrm, String name) {
        if (!postrm.contains(UNLOAD)) {
            return false;
        }
        for (final String line : postrm.lines().toList()) {
            final String text = line.strip();
            if (text.startsWith("for profile in ")
                    && List.of(text.split("[\\s;]+")).contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
