# Control-Plane IAM Mock Drill Runbook

## 1. Purpose

When no real IAM/RBAC environment is available, run a deterministic local/test drill that still validates:

1. JWKS + JWT auth chain (`external-iam` / `hybrid`).
2. Claim mapping and authorization matrix.
3. Failure drills (JWKS unavailable, timeout, hybrid fallback) and auth metrics-related alert rules.
4. Scripted evidence output in `simulated/mock` mode.

## 2. Execute

From repository root:

```bash
tools/mock-iam-rbac-drill.sh
```

## 3. Outputs

- `build/reports/mock-iam-drill/mock-iam-rbac-drill.json`
- `build/reports/mock-iam-drill/mock-iam-rbac-drill-summary.md`
- `build/reports/mock-iam-drill/mock-iam-jwks-jwt-chain.log`
- `build/reports/mock-iam-drill/mock-iam-claim-matrix.log`
- `build/reports/mock-iam-drill/mock-iam-failure-drill.log`
- `build/reports/mock-iam-drill/mock-iam-alert-rules-check.log`
- `build/reports/mock-iam-drill/mock-iam-preprod-closure-dry-run.log`

The JSON report includes:

- `mode: "simulated/mock"`
- per-phase pass/fail
- `overallPass`

## 4. Optional Overrides

- `MOCK_IAM_DRILL_CHAIN_TEST_COMMAND`
- `MOCK_IAM_DRILL_MATRIX_TEST_COMMAND`
- `MOCK_IAM_DRILL_FAILURE_TEST_COMMAND`
- `MOCK_IAM_DRILL_PREPROD_DRY_RUN_COMMAND`
- `MOCK_IAM_DRILL_ALERT_RULES_FILE`
- `MOCK_IAM_DRILL_REQUIRED_ALERTS`
- `MOCK_IAM_DRILL_REPORT_DIR`

## 5. Passing Criteria

- `overallPass=true` in `mock-iam-rbac-drill.json`.
- All required alerts exist in alert rules:
  - `ControlPlaneAuthDenyRateHigh`
  - `ControlPlaneExternalIamUnavailableSpike`
  - `ControlPlaneHybridFallbackSpike`

## 6. Limits

This is **not** production evidence. It does not prove:

- real IAM issuer/audience/JWKS connectivity in preprod/prod,
- real RBAC group-role mappings from external provider,
- real notification/on-call routing.
