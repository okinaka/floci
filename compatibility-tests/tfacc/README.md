# tfacc — upstream Terraform acceptance tests (PoC, S3 only)

Runs the **real** `hashicorp/terraform-provider-aws` acceptance suite
(`TestAcc*`) against a running Floci instance. Each upstream test performs its
own `terraform apply → assert → destroy` cycle, so this exercises semantic
behaviour — waiters, drift detection, attribute presence, multi-step
lifecycles — that a wire-shape harness cannot reach.

This is a proof of concept scoped to **S3**. It mirrors the approach the
fakecloud emulator uses in its `fakecloud-tfacc` crate (itself inspired by
the `localstack-terraform-test` project), reduced to a single service and a
shell driver.

## How it relates to the other harnesses here

| Harness | What it drives | Strength |
|---|---|---|
| `smithy-conformance` | synthetic variants over Smithy models, wire-level | breadth, fast, emulator-agnostic |
| `compat-terraform` | hand-written HCL via bats | small, curated smoke tests |
| **`tfacc` (this)** | the upstream provider's own `TestAcc*` Go suite | semantic depth: waiters, drift, real apply/destroy |

They are complementary. `tfacc` adds real-tool fidelity on top of the
wire-shape breadth `smithy-conformance` already provides.

## Prerequisites

- `go` (1.23+) on PATH
- `terraform` on PATH with a usable version selected
  (e.g. `tfenv install 1.14.5 && tfenv use 1.14.5`)
- A reachable Floci endpoint (default `http://localhost:4566`)

## Usage

```bash
# Start Floci (or point at an existing one)
docker run -d --rm -p 4566:4566 floci/floci:latest

# List every S3 acceptance test in the pinned provider
./tfacc.sh s3 --list

# Run the S3 allow-list against Floci
./tfacc.sh s3

# Point at a non-default endpoint (e.g. a 1.5.33 container on :4600)
FLOCI_ENDPOINT=http://localhost:4600 ./tfacc.sh s3
```

The first run shallow-clones the provider at the pinned tag into `.work/`
(git-ignored, ~300MB); later runs reuse it. Full `go test` output per service
is written to `.logs/<service>.log`.

## What it does

1. Verifies `go` + `terraform` are present (loud failure otherwise).
2. Clones `terraform-provider-aws` at `TFACC_PROVIDER_TAG` (default `v5.97.0`)
   and strips the `godebug tlskyber` pragma removed in Go 1.24.
3. Checks Floci is reachable.
4. Sets `TF_ACC=1`, dummy AWS creds, and `AWS_ENDPOINT_URL_{S3,STS,IAM,KMS}`
   (+ the default) to the Floci endpoint — AWS SDK Go v2 honours these, so the
   provider talks to Floci instead of AWS.
5. Runs `go test ./internal/service/s3/ -run <RUN_REGEX> -skip <deny>`.
6. Summarises pass/fail and surfaces `--- FAIL:` lines.

## Growing coverage

`services/s3.sh` is the allow-list. Two knobs, matching fakecloud's model:

- **`RUN_REGEX`** — widen to include more `TestAcc*` tests as Floci's S3
  coverage is verified. Start narrow, grow deliberately.
- **`DENY`** — quarantine specific upstream test names with a reason
  (`unsupportable` / `gap` / `hung`). Driving `gap` entries to zero is the point.

To add another service later: implement `services/<name>.sh` with the same
three variables. (A full rollout would also add a CI matrix that fans out one
job per service/shard, as fakecloud's `tfacc.yml` does.)

## Configuration (env)

| Var | Default | Meaning |
|---|---|---|
| `FLOCI_ENDPOINT` | `http://localhost:4566` | emulator endpoint |
| `TFACC_PROVIDER_TAG` | `v5.97.0` | pinned provider release |
| `TFACC_TIMEOUT` | `30m` | `go test -timeout` |
| `TFACC_PARALLEL` | `4` | `go test -parallel` |
| `TFACC_WORK_DIR` | `./.work` | provider clone location |
