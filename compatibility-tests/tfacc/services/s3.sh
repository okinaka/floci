# S3 allow-list for the tfacc PoC.
#
# Sourced by ../tfacc.sh. Defines which upstream terraform-provider-aws
# acceptance tests to run for S3, and which specific ones to skip (with a
# reason). Grow coverage by widening RUN_REGEX; quarantine failures by adding
# a DENY entry with a reason tag:
#
#   unsupportable  needs a feature Floci does not plan to implement
#   gap            fails on a real Floci bug — drive these to zero
#   hung           never completed in triage — characterise later
#
# Discover the exact upstream names with:  ../tfacc.sh s3 --list

# Directory under internal/service/ in terraform-provider-aws.
SERVICE="s3"

# Go -run regex (case-SENSITIVE — confirm names with `../tfacc.sh s3 --list`).
# A small foundational set across the core S3 resources, each verified to run
# against Floci 1.5.33: bucket lifecycle, ACL, versioning, and object.
RUN_REGEX='^TestAccS3Bucket_disappears$|^TestAccS3BucketACL_basic$|^TestAccS3BucketVersioning_basic$|^TestAccS3Object_basic$'

# Upstream TestAcc names to -skip, one per line with a reason
# (unsupportable | gap | hung). Driving `gap` entries to zero is the point.
DENY=(
  # gap: Floci does not echo the default SSE-S3 on objects, so the provider's
  # `server_side_encryption == "AES256"` check fails ("expected AES256, got \"\"").
  # Found by this PoC on floci 1.5.33 — object responses omit the default that
  # bucket-level GetBucketEncryption now returns.
  "TestAccS3Object_basic"
)
