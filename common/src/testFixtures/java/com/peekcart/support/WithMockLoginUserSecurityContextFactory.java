package com.peekcart.support;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.List;

/**
 * {@link WithMockLoginUser}에 선언된 값으로 SecurityContext를 구성한다.
 * Principal을 {@code Long}(userId), authority를 {@code ROLE_<role>}, Details를 {@code String}(familyId)로
 * 설정하여 {@code LoginUserArgumentResolver}(header-trust, PR3c)가 정상 동작하도록 한다.
 */
public class WithMockLoginUserSecurityContextFactory
        implements WithSecurityContextFactory<WithMockLoginUser> {

    @Override
    public SecurityContext createSecurityContext(WithMockLoginUser annotation) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                annotation.userId(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + annotation.role()))
        );
        auth.setDetails(annotation.familyId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        return context;
    }
}
