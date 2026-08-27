# E2E 시나리오 실행자 (계획 P3).
#
# 러너는 internal:true 네트워크 **안에서** 돈다 — 호스트에서 실행하려면 앱에 호스트 포트를
# 열어야 하고 그러면 격리 계약이 깨진다. 그래서 필요한 도구를 이 이미지에 담는다.
#   - mysql client : 4개 서비스 DB 의 상태 단언
#   - kafka-python : 토픽/consumer group readiness(active member·assigned partition) + 메시지 주입
#   - curl/jq      : actuator·stub 원장 조회
FROM python:3.12-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      default-mysql-client curl jq netcat-openbsd ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir "kafka-python-ng==2.2.3" "PyMySQL==1.1.1"

WORKDIR /work
