# control-plane

`control-plane` manages tenant-facing governance configuration.

Current scope:

- Spring Boot module scaffold
- HTTP API to upsert and query tenant policy
- configurable bearer token auth for `/api/v1/tenants/**`
- Redis-backed repository behind an interface for future storage evolution

Out of scope in this phase:

- External IAM / RBAC integration
- Persistent storage backend integration
- Cross-service dynamic policy distribution
