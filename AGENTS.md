# AGENTS.md

Guidance for AI agents (e.g. Claude Code) working in this repository.

## Project overview

`dns-monitor-dns-client` is a Spring Boot 3.x RESTful service that performs DNS lookups
via [dnsjava](https://github.com/dnsjava/dnsjava) and exposes the results as JSON.
It runs on port **8001** and is published as an OCI container image to Google Artifact Registry.

## Build and test

```bash
# Compile and run all tests
./gradlew build

# Run tests only
./gradlew test

# Run the service locally
./gradlew bootRun
```

Java 21 (Adoptium/Temurin) is required. The toolchain is declared in `build.gradle`; Gradle
will download it automatically if `asdf` or another toolchain provider is not already set up.

## Key source locations

| Path | Purpose |
|------|---------|
| `src/main/java/io/dnsmonitor/dns/client/DnsClientController.java` | REST endpoints |
| `src/main/java/io/dnsmonitor/dns/client/DnsClientWorker.java` | Orchestrates parallel DNS lookups |
| `src/main/java/io/dnsmonitor/dns/client/dnsjava/DnsJavaAdapter.java` | Wraps dnsjava |
| `src/main/java/io/dnsmonitor/dns/client/model/` | DNS record POJOs (A, AAAA, MX, …) |
| `src/main/java/io/dnsmonitor/dns/client/validators/` | `@Domain` and `@DnsRecordType` Jakarta constraint annotations |
| `src/main/java/io/dnsmonitor/dns/client/transform/ModelTransformer.java` | Maps dnsjava records to model POJOs |
| `src/main/resources/application.yml` | Spring configuration (port 8001, trace log pattern) |

## API endpoints

```
GET /dns/{domain}                  # Look up all supported record types for a domain
GET /dns/{recordtype}/{domain}     # Look up a specific record type
```

Supported record types: `A`, `AAAA`, `CAA`, `CNAME`, `MX`, `NS`, `SOA`, `SRV`, `TXT`.

Input validation is enforced via Jakarta Bean Validation annotations; invalid inputs return
HTTP 400 with a plain-text error message.

## Supported record types

When adding a new record type:
1. Add the type string to `DnsClientWorker.RECORD_TYPES`.
2. Add a mapping in `DnsJavaAdapter.convertRecordType()`.
3. Create a new POJO in `src/main/java/io/dnsmonitor/dns/client/model/`.
4. Add a mapping case in `ModelTransformer`.
5. Add a test in `DnsJavaAdapterTest`.

## Observability

The service uses Micrometer Tracing (OpenTelemetry bridge). Trace and span IDs appear in
every log line via the pattern configured in `application.yml`. Do not remove tracing spans
from the controller without a good reason.

## Container image

Built with [Jib](https://github.com/GoogleContainerTools/jib) (`./gradlew jib`).
Base image: `eclipse-temurin:21-noble`. Published to
`europe-docker.pkg.dev/dnsmonitor/containers/dns-client`. Publishing requires GCP credentials
and is handled by the CI pipeline — do not run `jib` locally unless you have explicit
registry access.

## CI / GitHub Actions

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `ci.yml` | Push / PR | Build and test |
| `publish.yml` | Push to `main` | Build and push container image |
| `release.yml` | Tag | Cut a release |
| `dependency-review.yml` | PR | Check for vulnerable dependencies |
| `dependency-submission.yml` | Push | Submit dependency graph to GitHub |

Secrets (`GOOGLE_CREDENTIALS`, etc.) are managed in GitHub Actions — never hard-code them.

## Coding conventions

- Java 21; use `var`, records, and sealed types where appropriate.
- Spring constructor injection only (no `@Autowired` on fields).
- Validation belongs in the `validators` package using Jakarta constraint annotations.
- Record type strings are always compared case-insensitively (`toUpperCase()`).
- Tests use JUnit 5 + XMLUnit (for record serialization assertions) + Spring Boot Test.
