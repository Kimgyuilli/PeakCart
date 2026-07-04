package com.peekcart.global.retention;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * retention floor 교차필드 불변식 검증 + max 계산 (ADR-0012 D5 · 구현 ② PR3).
 * 이 검증이 부팅 시 fail-fast 의 근거다({@code @Validated @ConfigurationProperties}).
 */
@DisplayName("IdempotencyRetentionProperties — floor cross-field 검증 + max 계산")
class IdempotencyRetentionPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private IdempotencyRetentionProperties props(Duration retention,
                                                 Duration kafka, Duration downtime,
                                                 Duration dlq, Duration backfill) {
        IdempotencyRetentionProperties p = new IdempotencyRetentionProperties();
        p.setRetention(retention);
        p.getFloor().setKafkaTopicRetention(kafka);
        p.getFloor().setMaxConsumerDowntime(downtime);
        p.getFloor().setDlqReplayWindow(dlq);
        p.getFloor().setBackfillReplayWindow(backfill);
        return p;
    }

    @Test
    @DisplayName("floor.max() 는 4창의 최댓값을 돌려준다")
    void floorMaxReturnsLargest() {
        IdempotencyRetentionProperties p = props(
                Duration.ofDays(7), Duration.ofDays(3), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(5));
        assertThat(p.getFloor().max()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("retention >= floor max 이면 위반 없음")
    void validWhenRetentionAtLeastFloor() {
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                Duration.ofDays(7), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7)));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("retention < floor max 이면 @AssertTrue 위반 (fail-fast 근거)")
    void violationWhenRetentionBelowFloor() {
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                Duration.ofDays(1), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7)));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("retentionAtLeastFloor"));
    }

    @Test
    @DisplayName("floor 창 누락(null)이면 @NotNull 위반 (@Valid cascade)")
    void violationWhenFloorWindowNull() {
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                Duration.ofDays(7), null, Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7)));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("floor.kafkaTopicRetention"));
    }
}
