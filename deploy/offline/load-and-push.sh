#!/usr/bin/env bash
# ============================================================================
# Step 3 (on the hospital host / jump box, NO internet required):
#   load every tar, retag to the local registry, push.
#
# Usage:
#   ./load-and-push.sh harbor.hospital.local/medassist [bundle-dir]
#
# Defaults: bundle-dir = ./offline-bundle
#
# Why retag: the tar keeps the original repo name (e.g. postgres:15-alpine),
# while the cluster pulls everything from the hospital Harbor through
# imageRegistry. Pushing both keeps values.yaml unchanged.
# ============================================================================
set -euo pipefail

REGISTRY_PREFIX="${1:-}"
BUNDLE_DIR="${2:-./offline-bundle}"

if [[ -z "$REGISTRY_PREFIX" ]]; then
  echo "usage: $0 <registry-prefix> [bundle-dir]" >&2
  echo "  e.g. $0 harbor.hospital.local/medassist" >&2
  exit 2
fi

if [[ ! -d "$BUNDLE_DIR" ]]; then
  echo "bundle dir not found: $BUNDLE_DIR" >&2
  exit 2
fi

echo "== loading images from $BUNDLE_DIR"
for tar in "$BUNDLE_DIR"/*.tar; do
  [[ -e "$tar" ]] || continue
  echo "-- docker load $tar"
  docker load -i "$tar"
done

echo "== retag + push"
# 业务镜像：本地构建 tag 为 medassist/platform-*:1.0.0（见 README §1.1），
# 推到院内 Harbor 后为 {REGISTRY_PREFIX}/platform-*:1.0.0，
# 与 Chart 的 imageRegistry + repository(platform-*) 拼接结果一致。
CHART_IMAGES=(
  "platform-gateway:1.0.0"
  "platform-auth:1.0.0"
  "platform-empi:1.0.0"
  "platform-clinical:1.0.0"
  "platform-pharmacy:1.0.0"
  "platform-tech:1.0.0"
  "ai-core:1.0.0"
)

# Third-party images: pushed with their original path under the same registry,
# so a single registry host serves everything (no egress at pull time).
THIRD_PARTY=(
  "postgres:15-alpine"
  "redis:7-alpine"
  "nacos/nacos-server:v2.5.1"
  "mysql:8.0.36"
  "milvusdb/milvus:v2.4.0"
  "quay.io/coreos/etcd:v3.5.14"
  "minio/minio:RELEASE.2024-12-18T13-15-44Z"
  "zilliz/attu:v2.4.12"
  "apache/skywalking-oap-server:11.0.0"
  "apache/skywalking-ui:11.0.0"
  "apache/skywalking-java-agent:9.8.0-java21"
  "grafana/loki:3.4.2"
  "grafana/promtail:3.4.2"
)

REGISTRY_HOST="${REGISTRY_PREFIX%%/*}"

push_image() {
  local src="$1"
  local dst="$2"
  echo "-- $src -> $dst"
  docker tag "$src" "$dst"
  docker push "$dst"
}

for img in "${CHART_IMAGES[@]}"; do
  # src = 本地构建 tag（medassist/platform-gateway:1.0.0）
  # dst = harbor.hospital.local/medassist/platform-gateway:1.0.0
  push_image "medassist/${img}" "${REGISTRY_PREFIX}/${img}"
done

for img in "${THIRD_PARTY[@]}"; do
  push_image "$img" "${REGISTRY_HOST}/${img}"
done

echo ""
echo "== done. Verify the cluster can pull with no egress:"
echo "   kubectl -n medassist get pods -w"
echo "   (all images must resolve to ${REGISTRY_HOST})"
