package com.peekcart.global.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLQ listener group 상수 ↔ {@link PeekcartService} 정본 정합 (계획 P5 · diff 리뷰 2R #3).
 *
 * <p>{@code @KafkaListener} 의 {@code groupId} 는 컴파일 상수여야 해서 {@link DlqTopology} 에
 * {@code String} 상수를 둘 수밖에 없다. 그런데 group 문자열의 <b>정본은 {@link PeekcartService}</b> 다
 * ({@code dlqListenerGroup()} · {@code quarantineListenerGroup()}).
 *
 * <p>정본이 둘이 되면 <b>리터럴 값만 서로 바꿔도</b> lint 는 통과한다 — annotation 이 참조하는
 * 상수 <i>이름</i>은 그대로이기 때문이다. 그 구멍을 여기서 막는다.
 */
@DisplayName("[SAGA-P5-DLQGROUP] DLQ listener group 상수는 PeekcartService 정본과 같다")
class DlqListenerGroupContractTest {

    @ParameterizedTest
    @EnumSource(PeekcartService.class)
    @DisplayName("[SAGA-P5-DLQGROUP] intake group 상수 == PeekcartService.dlqListenerGroup()")
    void intakeGroupMatchesCanonicalSource(PeekcartService service) {
        assertThat(DlqTopology.dlqIntakeGroup(service))
                .as("%s 의 DLQ intake group", service)
                .isEqualTo(service.dlqListenerGroup());
    }

    @ParameterizedTest
    @EnumSource(PeekcartService.class)
    @DisplayName("[SAGA-P5-DLQGROUP] quarantine group 상수 == PeekcartService.quarantineListenerGroup() (대상 없으면 null)")
    void quarantineGroupMatchesCanonicalSource(PeekcartService service) {
        String actual = DlqTopology.quarantineGroup(service);
        if (DlqTopology.quarantineTopics(service).isEmpty()) {
            assertThat(actual)
                    .as("%s 는 발행 토픽이 0개라 quarantine listener 가 없다", service)
                    .isNull();
        } else {
            assertThat(actual).isEqualTo(service.quarantineListenerGroup());
        }
    }

    @Test
    @DisplayName("[SAGA-P5-DLQGROUP] 두 group 집합은 서로소다 — 같은 값이면 한 레코드를 두 listener 가 집는다")
    void groupSetsAreDisjoint() {
        for (PeekcartService service : PeekcartService.values()) {
            assertThat(DlqTopology.dlqIntakeGroup(service))
                    .isNotEqualTo(DlqTopology.quarantineGroup(service));
        }
    }
}
