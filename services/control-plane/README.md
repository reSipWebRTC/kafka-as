# control-plane

`control-plane` manages tenant-facing governance configuration.

Current scope:

- Spring Boot module scaffold
- HTTP API to upsert and query tenant policy
- In-memory repository behind an interface for future persistence swap

Out of scope in this phase:

- Authentication and authorization
- Persistent storage backend integration
- Cross-service dynamic policy distribution
