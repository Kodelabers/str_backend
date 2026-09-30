package com.str.backend.registries.eovlastenja;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vrijednosti ulaze u XML tek nakon provjere formata — ništa ne smije promijeniti strukturu poruke. */
class EOvlastenjaRequestTest {

    @Test
    void buildsRequestForCompany() {
        EOvlastenjaRequest r = EOvlastenjaRequest.of("3B51-9ACB-EAE9-801A", "70000000004", "33333333360");

        assertThat(r.id()).matches("_[0-9a-f]{32}");
        assertThat(r.xml())
                .contains("Id=\"" + r.id() + "\"")
                .contains("<Sesija_Id>3B51-9ACB-EAE9-801A</Sesija_Id>")
                .contains("<PersonOIB>70000000004</PersonOIB>")
                // JipsTo i IdentfiersFor nose istu tvrtku, inače e-Ovlaštenja ne vraćaju Representation
                .containsSubsequence("<JipsTo>", "<b:IPS>33333333360</b:IPS>", "<IdentfiersFor>", "<b:IPS>33333333360</b:IPS>");
        assertThat(r.xml().charAt(0)).isEqualTo('<'); // bez BOM-a i vodećeg razmaka
    }

    @Test
    void buildsNavigationRequest_withoutJipsTo() {
        EOvlastenjaRequest r = EOvlastenjaRequest.navigation("3B51-9ACB-EAE9-801A", "70000000004");

        assertThat(r.id()).matches("_[0-9a-f]{32}");
        assertThat(r.xml())
                .startsWith("<?xml")
                .contains("<NavigationDataRequest Id=\"" + r.id() + "\"")
                .contains("<Sesija_Id>3B51-9ACB-EAE9-801A</Sesija_Id>")
                .contains("<PersonOIB>70000000004</PersonOIB>")
                .doesNotContain("JipsTo");
        assertThatThrownBy(() -> EOvlastenjaRequest.navigation("x</Sesija_Id>", "70000000004"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachRequestHasOwnId() {
        assertThat(EOvlastenjaRequest.of("s", "70000000004", "33333333360").id())
                .isNotEqualTo(EOvlastenjaRequest.of("s", "70000000004", "33333333360").id());
    }

    @Test
    void rejectsValuesThatCouldInjectXml() {
        assertThatThrownBy(() -> EOvlastenjaRequest.of("x</Sesija_Id><PersonOIB>1", "70000000004", "33333333360"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EOvlastenjaRequest.of("s", "7000000000<", "33333333360"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EOvlastenjaRequest.of("s", "70000000004", "3333333336"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
