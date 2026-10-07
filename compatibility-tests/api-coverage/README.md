# AWS API Coverage

Measures how much of the AWS API an emulator dispatches: every operation of
every service in [aws/api-models-aws](https://github.com/aws/api-models-aws) is
probed once, and the only question asked is whether the request reached a
handler. Behaviour is not checked.

This is a breadth measurement, deliberately separate from SDK behaviour
testing. One emulator takes about 6 seconds for all 436 services and 19,456
operations.

## Running

```bash
./coverage.sh [base-url] [label]
```

Defaults to `http://localhost:4566` and a label derived from the URL. Writes
`target/coverage-<label>.{md,json,tsv}`.

The Smithy models are a sparse checkout kept under `CONFORMANCE_MODELS_HOME`
(default `~/.cache/floci-conformance`), cloned on first use and pulled on each
run. **Set `CONFORMANCE_MODELS_PULL=0` to keep the current checkout**, so that
two emulators are compared on the same model revision. The revision is recorded
in every report.

To run the tool directly instead of through the script:

```bash
mvn -q compile exec:java -Dexec.args="--models ~/.cache/floci-conformance/api-models-aws/models \
    --base-url http://localhost:4566 --label floci --out target/coverage-floci"
```

Options: `--models DIR` (or `CONFORMANCE_MODELS_DIR`), `--base-url URL` (or
`FLOCI_BASE_URL`), `--label NAME`, `--out PREFIX`, `--services a,b,c` to probe
only those model directories, `--threads N` (default 8).

Put two or more runs side by side, one row per operation:

```bash
mvn -q exec:java -Dexec.mainClass=io.floci.coverage.CoverageCompareMain \
    -Dexec.args="--service sesv2 --out target/compare-sesv2.md \
        floci=target/coverage-floci.tsv fakecloud=target/coverage-fakecloud.tsv"
```

## Canary calibration

This is what makes the measurement trustworthy, and getting it wrong is the
difference between a plausible number and a wrong one.

Emulators serve paths that match nothing through catch-all routes, and answer
them with plausible AWS errors. fakecloud answers through API Gateway's
execute-api (`Stage not found: v1`); floci and ministack answer through S3's
path-style `/{Bucket}/{Key+}`. Counting any AWS-shaped error as "implemented"
reported fakecloud at 81.5% when the real figure was 46.3%.

So before trusting a service's probes, the tool sends one request that cannot
reach a handler and fingerprints the reply (status, error type, message with
the operation name and first path segment blanked out, because catch-all routes
echo them):

- RPC-style protocols (`rpcv2Cbor`, `awsJson1_0`, `awsJson1_1`, `awsQuery`,
  `ec2Query`) select the operation by name, so a made-up operation name is a
  clean canary.
- REST protocols select by path, so the canary is the operation's own HTTP
  method on a path that shares no segment with any AWS route.

A probe whose reply is identical to that fingerprint is counted as unrouted,
however legitimate its error looks.

**Do not aim the canary at the operation's own first path segment.** Floci's
routes are greedy enough that a real handler answers, which wrongly reported
s3tables and securityhub as unimplemented.

Services whose own paths start with a label (S3) are the catch-all, so there is
nothing to compare against and no calibration is applied.

## Known limits

- Only dispatch is measured, never correctness. A stub that returns a valid AWS
  error for anything counts as implemented.
- Services that share a path cannot be told apart (about 7 share
  `/tags/{resourceArn}`).
- Not fully independent of state: `Create` probes create real resources, so
  compare emulators on fresh instances. Verdicts are stable, but the HTTP
  status and error type of an individual operation can flip between runs when a
  sibling probe creates the resource it looks for.
- The request timeout is 10 seconds. A slow emulator can report `PROBE_FAILED`
  for an operation it does serve.
- api-models-aws `main` can carry bindings no released SDK uses yet, which
  count against an emulator that is current with the SDKs.

## Reference numbers

Model revision `7eb6ab9` (2026-10-02), 436 services, 19,456 operations.
Measured 2026-10-07 with `CONFORMANCE_MODELS_PULL=0`:

| Emulator | Implemented | Of all AWS |
|---|---:|---:|
| floci 2.2.0 | 4,065 | 20.9% |
| fakecloud 0.49.0 | 9,070 | 46.6% |
| ministack 1.5.20 | 4,092 | 21.0% |

Pin emulator images by digest for an exact comparison: a re-pushed tag moves
the number. The ministack figure above is 73 operations below a 2026-10-04
measurement of the same tag.

## Layout

`CoverageMain` walks the model catalog and hands each service to a
`ServiceProbe`, which encodes one request per operation, sends it, and lets
`CoverageClassifier` map the reply to a `CoverageStatus`. `CoverageReport`
writes the three output formats.

The `encode`, `invoke`, `synth` and `classify` packages are this module's own
copy of the AWS wire layer: per-protocol request encoders and invokers, the
SigV4 stub, and the input synthesizer that fills an operation's `@required`
members with format-plausible values so that routes told apart by a required
header or query parameter (S3 `CopyObject` versus `PutObject`) reach the right
handler. They were taken from the Smithy behaviour-conformance harness and
trimmed to what a dispatch probe needs; the two are independent from here on.
