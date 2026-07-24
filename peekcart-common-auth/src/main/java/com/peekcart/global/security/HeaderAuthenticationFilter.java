package com.peekcart.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Gateway 가 검증 후 주입한 신뢰 헤더({@code X-User-Id}/{@code X-User-Role}/{@code X-User-Family-Id})로
 * 인증을 세우는 필터(header-trust, ADR-0013 D3 · PR3c). 서명 검증은 하지 않는다 — Gateway 가 이미 검증했고,
 * 외부 유입 헤더는 Gateway 가 strip 후 재주입하며, 직접 경로는 NetworkPolicy 로 차단된다.
 *
 * <p><b>3-state 계약(계획 P31)</b>:
 * <ol>
 *   <li><b>세 헤더 전부 부재</b> → anonymous 로 체인 계속(공개 경로 보존, 보호 경로는 authorizeHttpRequests 가 401)</li>
 *   <li><b>{@code X-User-Id}(양의 정수)·{@code X-User-Role}(USER/ADMIN) 각 정확히 1개</b>(familyId 는 전환기 선택) → 인증</li>
 *   <li><b>그 외</b>(부분 존재·blank·중복 헤더·형식 오류·미허용 role) → 401 (500·anonymous fallback 아님)</li>
 * </ol>
 */
@RequiredArgsConstructor
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final String USER_FAMILY_ID_HEADER = "X-User-Family-Id";

    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");

    private final AuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        List<String> ids = headerValues(request, USER_ID_HEADER);
        List<String> roles = headerValues(request, USER_ROLE_HEADER);
        List<String> familyIds = headerValues(request, USER_FAMILY_ID_HEADER);

        // (1) 세 헤더 전부 부재 → anonymous 로 통과
        if (ids.isEmpty() && roles.isEmpty() && familyIds.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        // (3) 형식/개수 위반 → 401
        Long userId = parseSingleUserId(ids);
        String role = parseSingleRole(roles);
        boolean familyViolation = familyIds.size() > 1
                || (familyIds.size() == 1 && !StringUtils.hasText(familyIds.get(0)));
        if (userId == null || role == null || familyViolation) {
            reject(request, response);
            return;
        }

        // (2) 인증 확정 — principal=userId, authority=ROLE_<role>, details=familyId(전환기 레거시면 null)
        String familyId = familyIds.isEmpty() ? null : familyIds.get(0).trim();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        authentication.setDetails(familyId);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    /** 헤더의 모든 값을 원본 그대로 반환(부재=빈 리스트, 중복 헤더는 복수 값, present-blank 는 blank 값 포함). */
    private List<String> headerValues(HttpServletRequest request, String name) {
        return Collections.list(request.getHeaders(name));
    }

    /** 정확히 1개 + 양의 정수. 위반이면 {@code null}. */
    private Long parseSingleUserId(List<String> ids) {
        if (ids.size() != 1) {
            return null;
        }
        try {
            long value = Long.parseLong(ids.get(0).trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 정확히 1개 + 허용 role. 위반이면 {@code null}. */
    private String parseSingleRole(List<String> roles) {
        if (roles.size() != 1) {
            return null;
        }
        String role = roles.get(0).trim();
        return ALLOWED_ROLES.contains(role) ? role : null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            authenticationEntryPoint.commence(request, response,
                    new InsufficientAuthenticationException("신뢰 헤더 형식 오류"));
        } catch (ServletException e) {
            throw new IOException(e);
        }
    }
}
