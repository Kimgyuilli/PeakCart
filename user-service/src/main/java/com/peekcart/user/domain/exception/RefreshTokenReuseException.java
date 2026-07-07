package com.peekcart.user.domain.exception;

import com.peekcart.global.exception.ErrorCode;

/**
 * refresh token reuse(탈취) 감지 예외 (ADR-0013 D4). 응답 코드는 {@code USR-004}(유효하지 않은 토큰)로
 * 일반 무효 토큰과 구분되지 않지만, 타입을 분리해 {@code AuthService.refresh} 트랜잭션이 이 예외에서만
 * <b>롤백하지 않도록</b>({@code noRollbackFor}) 한다 — family 무효화가 요청 거부와 함께 되돌아가는 것을 막는다.
 */
public class RefreshTokenReuseException extends UserException {
    public RefreshTokenReuseException() {
        super(ErrorCode.USR_004);
    }
}
