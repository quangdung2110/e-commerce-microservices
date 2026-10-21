#!/usr/bin/env bash
# Triển khai một lớp của deploy/k8s lên context Kubernetes hiện tại.
#   bash deploy/k8s/apply.sh            # mặc định: infra
#   bash deploy/k8s/apply.sh infra
#   bash deploy/k8s/apply.sh services   # sau infra; sinh từ docker-compose.yml bằng generate.py
#   bash deploy/k8s/apply.sh observability
# configMapGenerator đọc file gốc ngoài thư mục kustomization (init-db.sh, infra/minio), nên
# build bằng kubectl kustomize --load-restrictor LoadRestrictionsNone thay vì kubectl apply -k.
set -euo pipefail

layer="${1:-infra}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dir="$root/$layer"
[ -f "$dir/kustomization.yaml" ] || { echo "không có lớp '$layer' ($dir)" >&2; exit 1; }

context="$(kubectl config current-context)"
if [ "$context" != "docker-desktop" ] && [ "${ALLOW_ANY_CONTEXT:-}" != "1" ]; then
  echo "context hiện tại là '$context', không phải docker-desktop; đặt ALLOW_ANY_CONTEXT=1 nếu chủ ý" >&2
  exit 1
fi

# Spec của Job không sửa được; các Job ở đây idempotent nên chạy lại mỗi lần apply.
case "$layer" in
  infra)
    kubectl -n data delete job minio-init --ignore-not-found >/dev/null 2>&1 || true
    ;;
  services)
    if command -v python >/dev/null 2>&1; then
      python "$root/generate.py" --check
    else
      echo "không có python: bỏ qua kiểm tra deploy/k8s/services còn khớp docker-compose.yml" >&2
    fi
    kubectl -n data delete job kafka-connect-init --ignore-not-found >/dev/null 2>&1 || true
    ;;
esac

kubectl kustomize --load-restrictor LoadRestrictionsNone "$dir" | kubectl apply -f -
