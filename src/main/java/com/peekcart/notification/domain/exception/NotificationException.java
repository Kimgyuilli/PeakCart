package com.peekcart.notification.domain.exception;

import com.peekcart.global.exception.BusinessException;
import com.peekcart.global.exception.ErrorCode;

/**
 * Notification 도메인에서 발생하는 비즈니스 예외.
 */
public class NotificationException extends BusinessException {

    public NotificationException(ErrorCode errorCode) {
        super(errorCode);
    }
}
