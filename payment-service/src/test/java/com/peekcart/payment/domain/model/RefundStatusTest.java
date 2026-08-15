package com.peekcart.payment.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 환불 원장 전이표 전수 검증 (ADR-0018 D2).
 * <p>특히 {@code UNRESOLVED} 에서 나가는 전이가 존재해야 한다 — 없으면 "미결로 남기지 않는다"가 깨진다.
 */
@DisplayName("RefundStatus 전이 규칙")
class RefundStatusTest {

    @Test
    @DisplayName("전이표 전수 — 허용 집합 외 전이는 전부 거부된다")
    void transitionMatrix() {
        assertAllowed(RefundStatus.REQUESTED, EnumSet.of(RefundStatus.CLAIMED));
        assertAllowed(RefundStatus.CLAIMED, EnumSet.of(
                RefundStatus.CLAIMED, RefundStatus.SUCCEEDED, RefundStatus.FAILED, RefundStatus.UNRESOLVED));
        // UNRESOLVED → CLAIMED: reconciliation 이 소유권을 잡는 전이. 상태를 UNRESOLVED 로 두면
        // 진행 중인 건을 운영자가 수동 종결할 수 있다(환불 성공 ↔ 원장 FAILED 불일치).
        assertAllowed(RefundStatus.UNRESOLVED,
                EnumSet.of(RefundStatus.SUCCEEDED, RefundStatus.FAILED, RefundStatus.CLAIMED));
        assertAllowed(RefundStatus.SUCCEEDED, EnumSet.noneOf(RefundStatus.class));
        assertAllowed(RefundStatus.FAILED, EnumSet.noneOf(RefundStatus.class));
    }

    @Test
    @DisplayName("UNRESOLVED 는 종결이 아니고 SUCCEEDED/FAILED 는 종결이다")
    void terminalClassification() {
        assertThat(RefundStatus.UNRESOLVED.isTerminal()).isFalse();
        assertThat(RefundStatus.REQUESTED.isTerminal()).isFalse();
        assertThat(RefundStatus.CLAIMED.isTerminal()).isFalse();
        assertThat(RefundStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(RefundStatus.FAILED.isTerminal()).isTrue();
    }

    private void assertAllowed(RefundStatus from, Set<RefundStatus> allowed) {
        for (RefundStatus target : RefundStatus.values()) {
            assertThat(from.canTransitionTo(target))
                    .as("%s → %s", from, target)
                    .isEqualTo(allowed.contains(target));
        }
    }
}
