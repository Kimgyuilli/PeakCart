package com.peekcart.order.domain.exception;

import com.peekcart.global.exception.BusinessException;
import com.peekcart.global.exception.ErrorCode;

/**
 * 주문 도메인에서 발생하는 비즈니스 예외.
 */
public class OrderException extends BusinessException {
    public OrderException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 어떤 입력이 문제였는지 호출자에게 알려야 할 때 사용한다
     * (예: 폐기된 페이지네이션 파라미터 이름).
     */
    public OrderException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
