package com.peekcart.global.deadletter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Function;

/**
 * DLQ 원장 종결 전이 (구현 ④-c-2b-1 P5 · ADR-0020 §D5-4).
 *
 * <p><b>종결의 단위는 행이 아니라 incident 다.</b> 재발행이 실패할 때마다 자식 행이 생기므로,
 * root 만 닫으면 자식이 미결로 남고 자식만 닫으면 <b>미결을 종결로 위장</b>한다. 그래서:
 * <ul>
 *   <li>대상 id 가 자식이면 <b>canonical root 로 정규화</b>한다</li>
 *   <li>root 를 잠근 뒤 root 와 <b>활성 자식 전부</b>를 같은 트랜잭션에서 전이한다</li>
 *   <li>{@code acknowledge}/{@code resolve}/{@code discard} 셋 다 이 경로를 쓴다 — 하나라도 빠지면 축이 갈라진다</li>
 * </ul>
 *
 * <p><b>잠금은 항상 root 부터</b> 잡는다. 재개방(④-c-2b-3)·purge 도 같은 순서로 진입하므로 순환이 없다.
 *
 * <p>발행 축({@code publication_status})은 여기서 건드리지 않는다 — 전이 주체는 reconciler 1종이다(§D6-4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterTransitionService {

    private final DeadLetterRecordJpaRepository repository;

    /** 전이 결과. {@code changed} 는 root 또는 자식 중 하나라도 실제로 전이했는지다. */
    public record Result(Long rootId, String status, boolean changed, int affectedChildren) {
    }

    @Transactional
    public Optional<Result> acknowledge(Long id, String actor) {
        return transition(id, record -> record.acknowledge(actor));
    }

    @Transactional
    public Optional<Result> resolve(Long id, String actor, String evidence) {
        return transition(id, record -> record.resolve(actor, evidence));
    }

    @Transactional
    public Optional<Result> discard(Long id, String actor, String reason) {
        return transition(id, record -> record.discard(actor, reason));
    }

    /**
     * @return 대상 id 가 원장에 없으면 empty
     */
    private Optional<Result> transition(Long id, Function<DeadLetterRecord, Boolean> apply) {
        // **엔티티가 아니라 root id 만 읽는다.** 여기서 엔티티를 읽으면 그 인스턴스가 영속성 컨텍스트에
        // 들어가고, 뒤의 SELECT ... FOR UPDATE 가 **잠금은 얻되 상태를 refresh 하지 않아** 잠금을
        // 기다리는 동안 다른 트랜잭션이 커밋한 terminal 전이를 못 본다. 그러면 "이미 terminal 이면 no-op"
        // 계약이 깨지고 나중 요청이 앞선 종결을 덮어쓴다.
        Optional<Long> rootIdOpt = repository.findRootIdOf(id);
        if (rootIdOpt.isEmpty()) {
            return Optional.empty();
        }

        Long rootId = rootIdOpt.get();
        Optional<DeadLetterRecord> locked = repository.findByIdForUpdate(rootId);
        if (locked.isEmpty()) {
            // 자식이 가리키는 root 가 사라진 상태 — 데이터 정합 문제이므로 조용히 삼키지 않는다.
            log.error("DLQ 원장 자식이 가리키는 root 가 없다 — id={}, rootRecordId={}", id, rootId);
            return Optional.empty();
        }

        DeadLetterRecord root = locked.get();
        boolean rootChanged = apply.apply(root);

        int affected = 0;
        // 조회가 이미 활성 자식만 돌려준다(terminal 은 잠그지도 않는다).
        for (DeadLetterRecord child : repository.findChildrenForUpdate(rootId)) {
            if (apply.apply(child)) {
                affected++;
            }
        }

        if (rootChanged || affected > 0) {
            log.info("DLQ 원장 전이 — rootId={}, status={}, 자식 {}건 (요청 id={})",
                    rootId, root.getStatus(), affected, id);
        }
        return Optional.of(new Result(rootId, root.getStatus(), rootChanged || affected > 0, affected));
    }
}
