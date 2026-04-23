# control-plane

`control-plane` manages tenant-facing governance configuration.

Current scope:

- Spring Boot module scaffold
- HTTP API to upsert, query, and rollback tenant policy (supports optional `targetVersion` / `distributionRegions` request body for rollback orchestration)
- configurable bearer token authz/authn for `/api/v1/tenants/**` (read/write + tenant scope)
- Redis-backed repository behind an interface for future storage evolution (with policy history snapshots for rollback)
- publish `tenant.policy.changed` after successful policy upsert/rollback, including orchestration metadata (`sourcePolicyVersion`, `targetPolicyVersion`, `distributionRegions`)

Out of scope in this phase:

- External IAM / RBAC integration
- Persistent storage backend integration
- Cross-region policy distribution and rollback orchestration
