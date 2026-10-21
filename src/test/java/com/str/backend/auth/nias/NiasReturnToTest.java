package com.str.backend.auth.nias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import static org.assertj.core.api.Assertions.assertThat;

/** Povratni put ne smije postati open-redirect: prolazi samo relativan put na našem FE-u. */
class NiasReturnToTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/new-registration-number",
            "/new-registration-number?facilityId=123&tip=FS_1",
            "/moji-rb#vrh",
            "/naselje?ime=Po%C5%BEega",
            "/"})
    void acceptsRelativePaths(String raw) {
        assertThat(NiasReturnTo.sanitize(raw)).contains(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "  ",
            "//evil.com/x",
            "/\\evil.com",
            "/a\\b",
            "https://evil.com/x",
            "javascript:alert(1)",
            "evil.com/x",
            "new-registration-number",
            "/x\r\nSet-Cookie: a=b",
            "/x\ty",
            "/x\u007fy",
            "/x y{",
            "/naselje?ime=Požega"})
    void rejectsAnythingElse(String raw) {
        assertThat(NiasReturnTo.sanitize(raw)).isEmpty();
    }

    @Test
    void rejectsTooLong() {
        String ok = "/" + "a".repeat(NiasReturnTo.MAX_LENGTH - 1);
        assertThat(NiasReturnTo.sanitize(ok)).isPresent();
        assertThat(NiasReturnTo.sanitize(ok + "a")).isEmpty();
    }

    @Test
    void target_keepsOriginOfSuccessUrl() {
        assertThat(NiasReturnTo.target("https://str-test-eturizam.gov.hr/new-registration-number", "/moji-rb?x=1"))
                .isEqualTo("https://str-test-eturizam.gov.hr/moji-rb?x=1");
        assertThat(NiasReturnTo.target("http://s-str-02.infodom.hr:8085/new-registration-number", "/a"))
                .isEqualTo("http://s-str-02.infodom.hr:8085/a");
    }

    @Test
    void redirectTarget_fallsBackToSuccessUrl() {
        String success = "https://str-test-eturizam.gov.hr/new-registration-number";
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(NiasReturnTo.redirectTarget(request, success)).isEqualTo(success);

        request.setAttribute(NiasReturnTo.REQUEST_ATTR, "/moji-rb");
        assertThat(NiasReturnTo.redirectTarget(request, success))
                .isEqualTo("https://str-test-eturizam.gov.hr/moji-rb");
    }

    @Test
    void successHandler_redirectsToRememberedPath() throws Exception {
        NiasSamlProperties props = new NiasSamlProperties(true, null, null, null, null, null, null, null,
                "https://str-test-eturizam.gov.hr/new-registration-number", null, null, null);
        AuthenticationSuccessHandler handler = new NiasSamlConfig().authenticationSuccessHandler(props, null);
        TestingAuthenticationToken auth = new TestingAuthenticationToken("user", "n/a");

        MockHttpServletRequest withPath = new MockHttpServletRequest();
        withPath.setAttribute(NiasReturnTo.REQUEST_ATTR, "/new-registration-number?facilityId=7");
        MockHttpServletResponse redirected = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(withPath, redirected, auth);
        assertThat(redirected.getRedirectedUrl())
                .isEqualTo("https://str-test-eturizam.gov.hr/new-registration-number?facilityId=7");

        MockHttpServletResponse fixed = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), fixed, auth);
        assertThat(fixed.getRedirectedUrl()).isEqualTo("https://str-test-eturizam.gov.hr/new-registration-number");
    }
}
