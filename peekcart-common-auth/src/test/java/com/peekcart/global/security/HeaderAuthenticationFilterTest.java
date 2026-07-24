package com.peekcart.global.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HeaderAuthenticationFilter} 3-state 계약 단위 회귀(PR3c).
 * ① 세 헤더 부재 → anonymous 통과 / ② 정상 → 인증(principal·role·familyId) / ③ 형식 오류 → 401(entrypoint), 체인 미진행.
 */
@DisplayName("HeaderAuthenticationFilter 3-state")
class HeaderAuthenticationFilterTest {

    /** 형식 오류 시 401 을 세팅하는 테스트용 entrypoint(운영은 JwtAuthenticationEntryPoint). */
    private final AuthenticationEntryPoint entryPoint =
            (req, res, ex) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    private final HeaderAuthenticationFilter filter = new HeaderAuthenticationFilter(entryPoint);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("세 헤더 전부 부재 → anonymous 로 체인 통과(인증 미설정)")
    void noHeaders_anonymousPassThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).as("체인 진행").isNotNull();
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("정상 헤더 → principal=userId·ROLE_role·details=familyId 로 인증")
    void validHeaders_authenticated() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "42");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "ADMIN");
        req.addHeader(HeaderAuthenticationFilter.USER_FAMILY_ID_HEADER, "fam-9");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(42L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(auth.getDetails()).isEqualTo("fam-9");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("family-less(X-User-Family-Id 부재) → 인증되고 details=null")
    void familyLess_authenticatedWithNullDetails() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "7");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "USER");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getDetails()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Role 누락(부분 존재) → 401, 체인 미진행")
    void partialHeaders_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        assertRejected(req);
    }

    @Test
    @DisplayName("비숫자 userId → 401")
    void nonNumericId_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "abc");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "USER");
        assertRejected(req);
    }

    @Test
    @DisplayName("음수/0 userId → 401")
    void nonPositiveId_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "0");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "USER");
        assertRejected(req);
    }

    @Test
    @DisplayName("미허용 role → 401")
    void invalidRole_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "ROOT");
        assertRejected(req);
    }

    @Test
    @DisplayName("중복 X-User-Id 헤더 → 401")
    void duplicateIdHeader_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "2");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "USER");
        assertRejected(req);
    }

    @Test
    @DisplayName("중복 X-User-Role 헤더 → 401")
    void duplicateRoleHeader_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "USER");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "ADMIN");
        assertRejected(req);
    }

    @Test
    @DisplayName("blank X-User-Id(존재하나 공백) → 401")
    void blankId_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "   ");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "USER");
        assertRejected(req);
    }

    @Test
    @DisplayName("blank X-User-Role(존재하나 공백) → 401")
    void blankRole_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "   ");
        assertRejected(req);
    }

    @Test
    @DisplayName("blank X-User-Family-Id(존재하나 공백) → 401")
    void blankFamilyId_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        req.addHeader(HeaderAuthenticationFilter.USER_ROLE_HEADER, "USER");
        req.addHeader(HeaderAuthenticationFilter.USER_FAMILY_ID_HEADER, "   ");
        assertRejected(req);
    }

    private void assertRejected(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).as("체인 미진행").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
