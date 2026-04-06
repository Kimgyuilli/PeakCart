#!/bin/bash
# kube-prometheus-stack 설치 스크립트 (minikube 환경)
set -euo pipefail

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace \
  -f "$(dirname "$0")/values-prometheus.yml"

echo ""
echo "=== 설치 완료 ==="
echo "Grafana:    minikube service kube-prometheus-stack-grafana -n monitoring"
echo "Prometheus: kubectl port-forward svc/kube-prometheus-stack-prometheus -n monitoring 9090:9090"
echo "ID/PW:      admin / admin"
