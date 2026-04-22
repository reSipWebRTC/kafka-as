# control-plane

`control-plane` manages tenant-facing governance configuration.

Current scope:

- Spring Boot module scaffold
- HTTP API to upsert and query tenant policy
- Redis-backed repository behind an interface for future storage evolution

Out of scope in this phase:

- Authentication and authorization
- Persistent storage backend integration
- Cross-service dynamic policy distribution
