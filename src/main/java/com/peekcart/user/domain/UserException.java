package com.peekcart.user.domain;

import com.peekcart.global.exception.BusinessException;
import com.peekcart.global.exception.ErrorCode;

public class UserException extends BusinessException {
    public UserException(ErrorCode errorCode) {
        super(errorCode);
    }
}
