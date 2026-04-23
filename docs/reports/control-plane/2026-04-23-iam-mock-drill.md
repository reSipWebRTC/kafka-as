# Control-Plane IAM Mock Drill Report (2026-04-23)

## 1. Scope

This drill is explicitly **simulated/mock** (no real external IAM/RBAC environment).

Validated items:

1. Local JWKS + JWT auth chain (`external-iam` / `hybrid`).
2. Claim mapping and authorization matrix (read/write, tenant scope, deny reasons).
3. Failure drills (JWKS unavailable, timeout, hybrid fallback) and related metric/alert checks.
4. Scripted closure output with machine-readable evidence.

## 2. Entry Command

```bash
tools/mock-iam-rbac-drill.sh
```

## 3. Evidence Files

- `build/reports/mock-iam-drill/mock-iam-rbac-drill.json`
- `build/reports/mock-iam-drill/mock-iam-rbac-drill-summary.md`

## 4. Result Summary

- `mode`: `simulated/mock`
- `overallPass`: `true`
- phase results:
  - `jwks-jwt-chain`: `PASS`
  - `claim-matrix`: `PASS`
  - `failure-drill`: `PASS`
  - `alert-rules-check`: `PASS`
  - `preprod-closure-dry-run`: `PASS`

## 5. Limitations

This report does **not** prove production readiness of external IAM integration. Real-environment closure still requires:

- real issuer/audience/JWKS connectivity and certificate-chain validation,
- real provider-side RBAC claim mapping validation,
- real preprod alert notification and on-call routing evidence.
