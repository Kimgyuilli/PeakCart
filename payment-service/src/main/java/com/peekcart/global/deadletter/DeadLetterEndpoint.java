package com.peekcart.global.deadletter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DLQ 원장 조회·종결 표면 (계획 ④-c-2a P10·P12).
 *
 * <p><b>왜 조회가 필요한가</b>: Slack 알림은 best-effort 라 놓칠 수 있고 일부 서비스에서는 no-op 이다.
 * 메트릭 계약(④-d 부모 P11)이 서기 전까지 "지금 미결이 몇 건이고 가장 오래된 건 얼마나 됐나" 를
 * 물어볼 수단이 있어야 한다 — 없으면 원장이 있어도 <b>운영이 관측 불능</b>이다.
 *
 * <p><b>왜 종결까지 여기서 하는가</b>: runbook 이 {@code UPDATE dead_letter_records SET status=...} 를
 * 지시하면 {@link DeadLetterRecord#discard} 의 <b>"사유 필수" 가드와 상태 전이 규칙이 통째로 우회</b>된다.
 * 그러면 그 가드는 코드에만 있고 운영에는 없는 장식이 되고, 리허설도 "SQL 이 돌았다" 만 증명하는
 * false-green 이 된다. 종결은 <b>도메인 메서드를 거치는 이 진입점</b>으로만 한다.
 *
 * <p>노출은 {@code management.endpoints.web.exposure.include} 의 {@code deadletter} 로 켜지며,
 * {@code ActuatorSecurityConfig} 의 permitAll 목록에 <b>없으므로</b> 인증 뒤에 있다.
 *
 * <p>재발행({@code REPLAY_*}/{@code RESOLVED})은 ④-c-2b 소관이라 여기 없다.
 */
@Component
@Endpoint(id = "deadletter")
@RequiredArgsConstructor
public class DeadLetterEndpoint {

    private final DeadLetterRecordJpaRepository repository;

    /** backlog 요약. {@code GET /actuator/deadletter} */
    @ReadOperation
    public Map<String, Object> backlog() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unresolved", repository.countUnresolved());

        repository.findOldestUnresolvedOccurredAt().ifPresentOrElse(oldest -> {
            result.put("oldestOccurredAt", oldest.toString());
            result.put("oldestAgeSeconds", Duration.between(oldest, LocalDateTime.now()).toSeconds());
        }, () -> {
            result.put("oldestOccurredAt", null);
            result.put("oldestAgeSeconds", 0L);
        });

        return result;
    }

    /**
     * 원장 1건을 전이한다. {@code POST /actuator/deadletter/{id}}
     *
     * <p>본문: {@code {"action":"acknowledge","actor":"..."}} 또는
     * {@code {"action":"discard","actor":"...","reason":"..."}}
     *
     * <p>{@code discard} 는 사유가 없으면 거부된다 — 근거 없이 닫힌 원장은 "해결됨" 과 구분되지 않는다.
     * 이미 전이된 건은 예외가 아니라 {@code changed=false} 로 응답한다(멱등).
     */
    @WriteOperation
    @Transactional
    public Map<String, Object> transition(@Selector Long id, String action, String actor, String reason) {
        Optional<DeadLetterRecord> found = repository.findById(id);
        if (found.isEmpty()) {
            return Map.of("error", "원장에 id=" + id + " 가 없습니다");
        }
        if (actor == null || actor.isBlank()) {
            return Map.of("error", "actor 는 필수입니다 — 누가 종결했는지 남지 않으면 감사가 불가능합니다");
        }

        DeadLetterRecord record = found.get();
        boolean changed;
        try {
            changed = switch (action == null ? "" : action) {
                case "acknowledge" -> record.acknowledge(actor);
                case "discard" -> record.discard(actor, reason);
                default -> throw new IllegalArgumentException(
                        "action 은 acknowledge 또는 discard 여야 합니다 (받은 값: " + action + ")");
            };
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("status", record.getStatus());
        result.put("changed", changed);
        return result;
    }
}
