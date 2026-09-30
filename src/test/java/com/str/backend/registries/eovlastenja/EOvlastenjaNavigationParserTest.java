package com.str.backend.registries.eovlastenja;

import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.registries.eovlastenja.EOvlastenjaException.Reason;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.str.backend.registries.eovlastenja.TestSignatures.TRUSTED;
import static com.str.backend.registries.eovlastenja.TestSignatures.errors;
import static com.str.backend.registries.eovlastenja.TestSignatures.navigationResponse;
import static com.str.backend.registries.eovlastenja.TestSignatures.representationItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Popis tvrtki iz {@code NavigationDataResponse}: samo tvrtke na temelju e-Zastupanja, unutar same
 * tvrtke (isti par koji potvrđuje provjera). Odgovor je nepotpisan, pa ostale provjere vrijede.
 */
class EOvlastenjaNavigationParserTest {

    private static final String REQUEST_ID = "_14aa7442f34946979997aaaa0da951bd";
    private static final String PERSON_OIB = "70000000004";

    private final EOvlastenjaResponseParser parser = new EOvlastenjaResponseParser(TRUSTED.cert());

    /** FINA primjer: e-Zastupanje FINA-e ulazi; e-Punomoć i djelovanje za drugu tvrtku ne ulaze. */
    @Test
    void finaSample_listsOnlyRepresentationWithinTheCompany() {
        List<ZastupanaTvrtka> companies = parse(navigationResponse(REQUEST_ID, PERSON_OIB, "", ""));

        assertThat(companies).containsExactly(new ZastupanaTvrtka("85821130368", "FINANCIJSKA AGENCIJA"));
    }

    /** e-Zastupanje bez LegalPersonTo (osoba kao građanin) također ulazi; obrt (IZVOR_REG=2) ne. */
    @Test
    void representationAsCitizen_isListed_craftIsNot() {
        String extra = representationItem("ĐURĐEVIĆ D.O.O.", "39986540678", "1")
                + representationItem("OBRT PERO", "19819819816", "2");

        List<ZastupanaTvrtka> companies = parse(navigationResponse(REQUEST_ID, PERSON_OIB, extra, ""));

        assertThat(companies).extracting(ZastupanaTvrtka::oib).containsExactly("85821130368", "39986540678");
        assertThat(companies.get(1).naziv()).isEqualTo("ĐURĐEVIĆ D.O.O.");
    }

    /** Unutar obrta (LegalPersonTo s IZVOR_REG≠1) — nije tvrtka u okviru koje se provjerava. */
    @Test
    void representationWithinNonCompany_isNotListed() {
        String extra = """
                <AuthorizationItem><LegalPersonTo>%s</LegalPersonTo><PermissionsFor><PermissionFor><EntityFor>\
                <Legal xmlns="http://eovlastenja.fina.hr/authorizationbase/v2">%s</Legal></EntityFor>\
                <AuthorizationRange>AllServices</AuthorizationRange><BasedOnRepresentation>true</BasedOnRepresentation>\
                <BasedOnAuthorization>false</BasedOnAuthorization></PermissionFor></PermissionsFor></AuthorizationItem>"""
                .formatted(TestSignatures.legal("OBRT", "39986540678", "2"), TestSignatures.legal("TVRTKA", "39986540678", "1"));

        assertThat(parse(navigationResponse(REQUEST_ID, PERSON_OIB, extra, "")))
                .extracting(ZastupanaTvrtka::oib).containsExactly("85821130368");
    }

    @Test
    void sameCompanyTwice_isListedOnce() {
        String extra = representationItem("FINANCIJSKA AGENCIJA", "85821130368", "1");

        assertThat(parse(navigationResponse(REQUEST_ID, PERSON_OIB, extra, ""))).hasSize(1);
    }

    @Test
    void sessionError_isSession() {
        assertThatThrownBy(() -> parse(navigationResponse(REQUEST_ID, PERSON_OIB, "", errors("203"))))
                .isInstanceOfSatisfying(EOvlastenjaException.class, e -> assertThat(e.reason()).isEqualTo(Reason.SESSION));
    }

    @Test
    void accessNotAllowed_isRegistryFailure() {
        assertThatThrownBy(() -> parse(navigationResponse(REQUEST_ID, PERSON_OIB, "", errors("100"))))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("100");
    }

    @Test
    void responseForAnotherRequest_isRejected() {
        assertThatThrownBy(() -> parse(navigationResponse("_drugi", PERSON_OIB, "", "")))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("ForRequestId=_drugi");
    }

    @Test
    void responseForAnotherPerson_isRejected() {
        assertThatThrownBy(() -> parse(navigationResponse(REQUEST_ID, "55555555551", "", "")))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("osoba");
    }

    @Test
    void doctype_isRejected() {
        String xml = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + navigationResponse(REQUEST_ID, PERSON_OIB, "", "");

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class);
    }

    /** Potpisani odgovor na provjeru nije popis — root se provjerava i ovdje. */
    @Test
    void permissionResponse_isNotANavigationResponse() {
        String xml = TestSignatures.response(REQUEST_ID, PERSON_OIB, "85821130368", TestSignatures.representation(), "");

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("root");
    }

    private List<ZastupanaTvrtka> parse(String xml) {
        return parser.parseNavigation(xml.getBytes(StandardCharsets.UTF_8), REQUEST_ID, PERSON_OIB);
    }
}
