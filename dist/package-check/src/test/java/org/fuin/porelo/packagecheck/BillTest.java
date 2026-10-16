package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BillTest {

    private static final String PATH = "/usr/share/porelo/sbom/porelo.cdx.json";

    private final Reports out = new Reports();

    private final Bill bill = new Bill("the porelo deb", "porelo-dist-deb", "0.1.0-SNAPSHOT", Main.SHIPS, Main.NEVER);

    @Test
    void passesABillThatNamesWhatShipsNestedOnesIncluded() {
        bill.check(bom("porelo-dist-deb", "0.1.0-SNAPSHOT", """
                {"name":"porelo-app","components":[{"name":"porelo-core"},{"name":"picocli"}]},{"name":"porelod"}"""), PATH, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text()).contains("PASS  the porelo deb ships a bill for itself (4 components)");
    }

    @Test
    void failsAPackageWithNoBill() {
        bill.check(null, PATH, out.report);

        assertThat(out.text()).contains("FAIL  the porelo deb ships no bill at " + PATH);
    }

    @Test
    void namesWhatShipsAndIsMissing() {
        bill.check(bom("porelo-dist-deb", "0.1.0-SNAPSHOT", """
                {"name":"porelo-app"},{"name":"porelod"}"""), PATH, out.report);

        assertThat(out.text()).contains("FAIL  the porelo deb ships a bill that is missing what ships: picocli, porelo-core");
    }

    @Test
    void namesWhatOnlyBuildsOrTestsThePackage() {
        // The aggregate bill this replaced named cucumber and sshj from the acceptance kit.
        bill.check(bom("porelo-dist-deb", "0.1.0-SNAPSHOT", """
                {"name":"porelo-app"},{"name":"porelod"},{"name":"porelo-core"},{"name":"picocli"},{"name":"sshj"},
                {"name":"x","components":[{"name":"cucumber-core"}]}"""), PATH, out.report);

        assertThat(out.text()).contains("names what does not ship: cucumber-core, sshj");
    }

    @Test
    void failsABillForAnotherSubject() {
        bill.check(bom("porelo", "0.1.0-SNAPSHOT", "{\"name\":\"porelo-app\"}"), PATH, out.report);

        assertThat(out.text()).contains("ships a bill that names porelo, not porelo-dist-deb");
    }

    @Test
    void failsABillForAnotherVersion() {
        bill.check(bom("porelo-dist-deb", "0.0.9", "{\"name\":\"porelo-app\"}"), PATH, out.report);

        assertThat(out.text()).contains("has version 0.0.9, not 0.1.0-SNAPSHOT");
    }

    @Test
    void failsABillThatListsNothing() {
        new Bill("the agent deb", "porelo-dist-deb", "0.1.0-SNAPSHOT", Set.of(), Set.of())
                .check(bom("porelo-dist-deb", "0.1.0-SNAPSHOT", ""), PATH, out.report);

        assertThat(out.text()).contains("lists no components at all");
    }

    @Test
    void failsADocumentThatIsNotABill() {
        bill.check("{\"bomFormat\":\"SPDX\"}", PATH, out.report);
        bill.check("not json", PATH, out.report);

        assertThat(out.text()).contains("is not a CycloneDX document").contains("is not JSON");
        assertThat(out.report.failures()).isEqualTo(2);
    }

    @Test
    void readsASnapshotPackageVersionAsTheMavenOne() {
        assertThat(Bill.mavenVersion("0.1.0~snapshot.69")).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(Bill.mavenVersion("0.1.0~snapshot.0+local.20260928T044029")).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(Bill.mavenVersion("0.1.0~SNAPSHOT")).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(Bill.mavenVersion("0.1.0")).isEqualTo("0.1.0");
    }

    private static String bom(String subject, String version, String components) {
        return """
                {"bomFormat":"CycloneDX","metadata":{"component":{"name":"%s","version":"%s"}},"components":[%s]}"""
                .formatted(subject, version, components);
    }

}
