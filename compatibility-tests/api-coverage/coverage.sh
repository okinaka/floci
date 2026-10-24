#!/usr/bin/env bash
# Measure how much of the AWS API an emulator dispatches.
#
#   ./coverage.sh [base-url] [label]
#
# Probes every operation of every service in aws/api-models-aws once, against
# the emulator at base-url (default http://localhost:4566), and writes
# target/coverage-<label>.{md,json,tsv}. The models are a sparse checkout kept
# under CONFORMANCE_MODELS_HOME (default ~/.cache/floci-conformance) and
# pulled to the latest commit on each run; set CONFORMANCE_MODELS_PULL=0 to
# keep the current checkout, so that two emulators are compared on the same
# model revision.
set -euo pipefail

base_url="${1:-http://localhost:4566}"
label="${2:-$(echo "$base_url" | sed -E 's#^https?://##; s#[^A-Za-z0-9.-]+#-#g')}"
home="${CONFORMANCE_MODELS_HOME:-$HOME/.cache/floci-conformance}"
repo="$home/api-models-aws"

if [ ! -d "$repo/.git" ]; then
    mkdir -p "$home"
    git clone -q --depth 1 --filter=blob:none --sparse https://github.com/aws/api-models-aws.git "$repo"
    git -C "$repo" sparse-checkout set models
elif [ "${CONFORMANCE_MODELS_PULL:-1}" != "0" ]; then
    git -C "$repo" pull -q --depth 1
fi

cd "$(dirname "$0")"
mvn -q compile exec:java -Dexec.args="--models $repo/models --base-url $base_url --label $label --out target/coverage-$label"
