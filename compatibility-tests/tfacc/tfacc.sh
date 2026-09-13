#!/usr/bin/env bash
#
# tfacc — run upstream terraform-provider-aws acceptance tests against Floci.
#
# PoC scope: S3 only. This drives the *real* HashiCorp provider test suite
# (TestAcc*), which does its own terraform apply/plan/destroy cycle and
# asserts on resource state — semantic coverage (waiters, drift, attribute
# presence) that the smithy-conformance harness does not reach.
#
# Prior art: fakecloud's `crates/fakecloud-tfacc` and
# bblommers/localstack-terraform-test. Like fakecloud we use an allow-list
# (opt in per implemented service) plus a per-service deny-list of specific
# TestAcc names, each with a reason.
#
# Usage:
#   ./tfacc.sh s3            # run the S3 allow-listed tests against Floci
#   ./tfacc.sh s3 --list     # list every TestAcc* name in internal/service/s3
#   FLOCI_ENDPOINT=http://localhost:4600 ./tfacc.sh s3
#
# Requires: go, terraform on PATH, and a reachable Floci endpoint.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="${TFACC_WORK_DIR:-$SCRIPT_DIR/.work}"
LOG_DIR="$SCRIPT_DIR/.logs"

# Pinned upstream provider tag. Bumping is a deliberate edit: a newer tag may
# add acc tests that assume attributes Floci does not yet return. Matches the
# tag fakecloud pins so triage notes stay comparable.
PROVIDER_REPO="${TFACC_PROVIDER_REPO:-https://github.com/hashicorp/terraform-provider-aws.git}"
PROVIDER_TAG="${TFACC_PROVIDER_TAG:-v5.97.0}"
PROVIDER_DIR="$WORK_DIR/terraform-provider-aws-$PROVIDER_TAG"

FLOCI_ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4566}"
GO_TIMEOUT="${TFACC_TIMEOUT:-30m}"
PARALLEL="${TFACC_PARALLEL:-4}"

err() { printf '\033[31m%s\033[0m\n' "$*" >&2; }
info() { printf '\033[36m%s\033[0m\n' "$*" >&2; }

require_toolchain() {
  local missing=()
  command -v go >/dev/null 2>&1 || missing+=("go")
  # tfenv installs a `terraform` shim that errors until a version is selected;
  # `terraform version` failing counts as missing so the message is actionable.
  terraform version >/dev/null 2>&1 || missing+=("terraform")
  if [ ${#missing[@]} -ne 0 ]; then
    err "tfacc requires ${missing[*]} on PATH (and terraform must have a usable version)."
    err "Install them before exercising the upstream Terraform acceptance suite."
    err "  go:        https://go.dev/dl/"
    err "  terraform: https://developer.hashicorp.com/terraform/install  (or 'tfenv install 1.14.5 && tfenv use 1.14.5')"
    exit 127
  fi
}

setup_provider() {
  # go.mod is the completion sentinel — a bare dir check can be true mid-clone.
  if [ ! -f "$PROVIDER_DIR/go.mod" ]; then
    info "Cloning $PROVIDER_REPO @ $PROVIDER_TAG (shallow) ..."
    mkdir -p "$WORK_DIR"
    rm -rf "$PROVIDER_DIR"
    git clone --depth 1 --branch "$PROVIDER_TAG" "$PROVIDER_REPO" "$PROVIDER_DIR"
  fi
  # Go >= 1.24 removed the `godebug tlskyber` pragma; strip it so the module
  # builds. Harmless on older Go.
  if grep -q 'godebug tlskyber' "$PROVIDER_DIR/go.mod" 2>/dev/null; then
    info "Stripping 'godebug tlskyber' from go.mod (Go >= 1.24) ..."
    grep -v 'godebug tlskyber' "$PROVIDER_DIR/go.mod" > "$PROVIDER_DIR/go.mod.tmp"
    mv "$PROVIDER_DIR/go.mod.tmp" "$PROVIDER_DIR/go.mod"
  fi
}

check_floci() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 4 "$FLOCI_ENDPOINT/" 2>/dev/null || echo 000)"
  if [ "$code" = "000" ]; then
    err "Floci is not reachable at $FLOCI_ENDPOINT (set FLOCI_ENDPOINT)."
    err "Start it, e.g.: docker run -d --rm -p 4566:4566 floci/floci:latest"
    exit 1
  fi
  info "Floci reachable at $FLOCI_ENDPOINT (HTTP $code)."
}

# Route the AWS SDK Go v2 at Floci. terraform-provider-aws honours
# AWS_ENDPOINT_URL_<SERVICE>; the provider resolves the account id via STS
# GetCallerIdentity, so STS must be pointed at Floci too. IAM/KMS are common
# secondaries on the S3 write path.
export_endpoints() {
  export AWS_ENDPOINT_URL="$FLOCI_ENDPOINT"
  export AWS_ENDPOINT_URL_S3="$FLOCI_ENDPOINT"
  export AWS_ENDPOINT_URL_STS="$FLOCI_ENDPOINT"
  export AWS_ENDPOINT_URL_IAM="$FLOCI_ENDPOINT"
  export AWS_ENDPOINT_URL_KMS="$FLOCI_ENDPOINT"
}

main() {
  local service="${1:-s3}"
  local mode="${2:-run}"

  local svc_config="$SCRIPT_DIR/services/$service.sh"
  if [ ! -f "$svc_config" ]; then
    err "No allow-list config for service '$service' (expected $svc_config)."
    err "This PoC ships S3 only. Add services/<name>.sh to extend."
    exit 2
  fi

  require_toolchain
  setup_provider

  # Sourced config provides: SERVICE, RUN_REGEX, DENY (bash array).
  # shellcheck source=/dev/null
  DENY=()
  source "$svc_config"
  local svc_path="./internal/service/${SERVICE}/"

  if [ "$mode" = "--list" ]; then
    info "Listing TestAcc* in $svc_path ..."
    ( cd "$PROVIDER_DIR" && go test "$svc_path" -list '^TestAcc' )
    exit 0
  fi

  check_floci
  export_endpoints
  export TF_ACC=1
  export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
  export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
  export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
  export AWS_REGION="${AWS_REGION:-us-east-1}"

  local skip_re=""
  if [ ${#DENY[@]} -ne 0 ]; then
    skip_re="^($(IFS='|'; echo "${DENY[*]}"))$"
  fi

  mkdir -p "$LOG_DIR"
  local log_file="$LOG_DIR/${service}.log"

  info "Running $service acceptance tests against Floci"
  info "  provider : $PROVIDER_TAG"
  info "  run      : $RUN_REGEX"
  info "  skip     : ${skip_re:-<none>}"
  info "  endpoint : $FLOCI_ENDPOINT"
  echo >&2

  local -a args=(
    test "$svc_path"
    -run "$RUN_REGEX"
    -v -count=1
    -timeout "$GO_TIMEOUT"
    -parallel "$PARALLEL"
  )
  [ -n "$skip_re" ] && args+=(-skip "$skip_re")

  set +e
  ( cd "$PROVIDER_DIR" && go "${args[@]}" ) 2>&1 | tee "$log_file"
  local rc=${PIPESTATUS[0]}
  set -e

  echo >&2
  local passed failed
  passed="$(grep -c -- '--- PASS:' "$log_file" 2>/dev/null || echo 0)"
  failed="$(grep -c -- '--- FAIL:' "$log_file" 2>/dev/null || echo 0)"
  if [ "$rc" -eq 0 ]; then
    info "tfacc $service: PASS ($passed passed, $failed failed) — log: $log_file"
  else
    err "tfacc $service: FAIL ($passed passed, $failed failed) — log: $log_file"
    grep -- '--- FAIL:' "$log_file" 2>/dev/null | sed 's/^/  /' >&2 || true
  fi
  exit "$rc"
}

main "$@"
