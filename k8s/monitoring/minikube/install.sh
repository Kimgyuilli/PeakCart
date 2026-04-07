#!/bin/bash
# kube-prometheus-stack 설치 스크립트 (minikube 환경)
#
# 사전 조건: monitoring 네임스페이스가 이미 존재해야 합니다.
#   kubectl apply -f k8s/monitoring/namespace.yml
#
# 배포 순서는 docs/02-architecture.md §12 참고.
set -euo pipefail

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  -f "$(dirname "$0")/values-prometheus.yml"

echo ""
echo "=== 설치 완료 ==="
echo "Grafana:    minikube service kube-prometheus-stack-grafana -n monitoring"
echo "Prometheus: kubectl port-forward svc/kube-prometheus-stack-prometheus -n monitoring 9090:9090"
echo "ID/PW:      admin / admin"
