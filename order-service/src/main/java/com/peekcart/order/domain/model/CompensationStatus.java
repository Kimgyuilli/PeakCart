package com.peekcart.order.domain.model;

/**
 * 보상 원장 처리 상태 (GW-2 #2). RESOLVED 전이는 환불 요청 경로(계획 P8) 소관이다.
 */
public enum CompensationStatus {
    OPEN,
    RESOLVED
}
