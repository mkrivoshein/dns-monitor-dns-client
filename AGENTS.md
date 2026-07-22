# AGENTS.md

Guidance for AI agents (e.g. Claude Code) working in this repository.

## Project overview

`dns-monitor-dns-client` is a Spring Boot 4.x RESTful service that performs DNS lookups
via [dnsjava](https://github.com/dnsjava/dnsjava) and exposes the results as JSON.
It runs on port **8001** by default and is published as an OCI container image to Google Artifact Registry.

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
| `src/main/java/io/dnsmonitor/dns/client/config/ConditionalTlsEnvironmentPostProcessor.java` | Enables HTTPS/mTLS from startup TLS properties |
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

## HTTP, HTTPS, and mTLS

The service starts as plain HTTP when no TLS certificate/key is configured. Supplying both
a server certificate and private key at startup enables HTTPS. Supplying a client CA in
addition enables mTLS by requiring clients to present certificates signed by that CA.

Supported startup properties:

| Property | Environment aliases | Purpose |
|----------|---------------------|---------|
| `dns.client.tls.certificate` | `DNS_CLIENT_TLS_CERTIFICATE`, `DNS_CLIENT_TLS_CERT` | Server certificate path/resource |
| `dns.client.tls.private-key` | `DNS_CLIENT_TLS_PRIVATE_KEY`, `DNS_CLIENT_TLS_KEY` | Server private key path/resource |
| `dns.client.tls.client-ca` | `DNS_CLIENT_TLS_CLIENT_CA` | Client CA certificate path/resource; enables required client auth |

Absolute filesystem paths are converted to `file:` resource URLs automatically. Relative
paths, `classpath:`, explicit `file:` URLs, and `https:` URLs are passed through to Spring
Boot SSL bundles. Plain `http:` URLs are rejected for TLS resources. TLS files are loaded
at startup; restart the app/container after certificate, private key, or client CA
rotation. When TLS is configured, a daemon scheduled executor named
`dns-client-cert-expiry-monitor` checks every certificate in the server PEM
(leaf and intermediates) and every certificate in the client CA bundle, when
present, immediately and then every 5 minutes. The monitor exits on the
earliest `notAfter` that falls within 10 minutes of the check time, naming the
offending chain member in the log. Exiting with a non-zero status lets the
container supervisor restart the app. Do not commit certificates, private
keys, or CA material to the repository.

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

## Versioning

`project.version` is derived from the nearest git tag by
[axion-release-plugin](https://github.com/allegro/axion-release-plugin) (1.21.x).
Tags use the `v<version>` prefix (e.g. `v5.1.0`), matching the project's existing tag
convention. A tagged commit produces the bare tag version (e.g. `5.1.0`).

Off-tag commits are decorated to make snapshots obvious in image tags:

- on `main` without a tag at HEAD: `<next>-rc` (e.g. `5.1.1-rc`)
- on a feature branch: `<next>-<short-sha>-rc` (e.g. `5.1.1-c143976-rc`)

Release flow:

1. Cut a GPG-signed annotated tag on `main`:
   `git tag -s -a -m "Release v5.2.0" v5.2.0`
2. Push the tag: `git push origin v5.2.0`
3. The `publish.yml` workflow builds and pushes the container image
   (`europe-docker.pkg.dev/dnsmonitor/containers/dns-client:5.2.0` and `:latest`).

`./gradlew currentVersion` prints the resolved version; use it to confirm what Jib will
produce. To override the resolved version when cutting a release:

```bash
./gradlew markNextVersion -Prelease.version=5.2.0
```

`release` is restricted to the `main` branch.

## Gradle build performance

`gradle.properties` enables the Gradle build cache and the configuration cache
(`org.gradle.caching=true`, `org.gradle.configuration-cache=true`,
`org.gradle.configuration-cache.problems=warn`). All tasks benefit except
`jib` / `jibDockerBuild` / `jibBuildTar`, which Jib 3.5.x marks
`notCompatibleWithConfigurationCache` in `build.gradle` because the plugin
captures `org.gradle.api.Project` in its configuration-time closures. This
is tracked upstream in
[GoogleContainerTools/jib#3132](https://github.com/GoogleContainerTools/jib/issues/3132);
remove the workaround and re-test on every Jib upgrade.

## CI / GitHub Actions

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `ci.yml` | Push / PR | Build and test |
| `publish.yml` | Push to `main` | Build and push container image |
| `release.yml` | Tag | Cut a release |
| `dependency-review.yml` | PR | Check for vulnerable dependencies |
| `dependency-submission.yml` | Push | Submit dependency graph to GitHub |

Secrets (`GOOGLE_CREDENTIALS`, etc.) are managed in GitHub Actions — never hard-code them.

## Commit Format

Use [Conventional Commits](https://www.conventionalcommits.org/) with GPG signing.

When adding an AI attribution trailer, use the one that matches the assistant
that made the change:

Co-Authored-By: Grok (x-ai/grok-code-fast-1) <kilo@kilo.ai>
Co-Authored-By: Codex (GPT-5) <codex@openai.com>
Co-Authored-By: Gemini 3 Flash <noreply@google.com>
Co-Authored-By: Claude (claude-opus-4-7) <noreply@anthropic.com>
Co-Authored-By: Claude (claude-sonnet-4-6) <noreply@anthropic.com>
Co-Authored-By: MiniMax-M3 <noreply@minimax.io>

Common types: `feat`, `fix`, `test`, `refactor`, `chore`.
Common scopes: `meter`, `invoice`, `pricing`, `metrics`, `vault`, `stripe`, `xero`.

If signing fails due to a locked key, stop and wait — do not fall back to an unsigned commit.

## Coding conventions

- Java 21; use `var`, records, and sealed types where appropriate.
- Spring constructor injection only (no `@Autowired` on fields).
- Validation belongs in the `validators` package using Jakarta constraint annotations.
- Record type strings are always compared case-insensitively (`toUpperCase()`).
- Tests use JUnit 5 + XMLUnit (for record serialization assertions) + Spring Boot Test.
