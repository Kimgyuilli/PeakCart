현재 프로젝트 상태를 파악합니다.

다음을 순서대로 읽고 이해하세요:

1. `docs/TASKS.md` — 현재 Phase, 각 Task 상태, 진행 중인 항목
2. 현재 Phase에 해당하는 `docs/progress/PHASE{N}.md` — 최근 작업 이력, 주요 결정 사항
3. 진행 중(`🔄`) Task가 있다면, 해당 Task 목표에 관련된 설계 문서를 선택적으로 읽습니다:
   - 기능 범위가 불명확하면: `docs/03-requirements.md`
   - 아키텍처/패키지 구조가 필요하면: `docs/02-architecture.md`
   - DB 스키마/인덱스가 필요하면: `docs/05-data-design.md`
   - 설계 결정 근거가 필요하면: `docs/04-design-deep-dive.md`
4. 진행 중 Task에 해당하는 실제 소스 파일 존재 여부 확인
5. 최근 git log (`git log --oneline -10`)

파악한 내용을 아래 형식으로 요약 보고하세요:

**현재 Phase**: ...
**진행 중인 Task**: ...
**완료된 항목**: (Task 내 체크된 항목 수 / 전체)
**최근 주요 결정**: (progress 문서에서 파악한 최근 결정 사항 1~2줄)
**구현된 파일**: (실제 존재하는 소스 파일 목록, 없으면 "없음")
**다음 작업**: (미완료 항목 중 첫 번째)
