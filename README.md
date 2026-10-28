# dns-monitor-dns-client

A RESTful DNS lookup service built with Spring Boot. Given a domain name it queries all common DNS record types in parallel and returns the results as JSON. Part of the [dns-monitor](https://github.com/mkrivoshein) platform.

## API

### Look up all record types

```
GET /dns/{domain}
```

Returns all DNS records found across all supported record types.

### Look up a specific record type

```
GET /dns/{recordtype}/{domain}
```

**Supported record types:** `A`, `AAAA`, `CAA`, `CNAME`, `MX`, `NS`, `SOA`, `SRV`, `TXT`

### Example

```bash
curl http://localhost:8001/dns/example.com
curl http://localhost:8001/dns/MX/example.com
```

**Response:**

```json
{
  "domain": "example.com",
  "records": [
    { "type": "A", "address": "93.184.216.34" },
    { "type": "MX", "priority": 0, "target": "." }
  ]
}
```

Invalid domains or unsupported record types return HTTP 400.

## Running locally

**Prerequisites:** Java 25 (BellSoft Liberica)

```bash
./gradlew bootRun
```

The service listens on port **8001** by default.

```bash
./gradlew build   # compile + test
./gradlew test    # tests only
```

## HTTPS and mTLS

By default the service starts with plain HTTP. If a server certificate and private key are
provided at startup, the app enables HTTPS. If a client CA is also provided, the app
requires client certificates and verifies them against that CA.

Supported properties and environment variables:

| Property | Environment aliases | Description |
|----------|---------------------|-------------|
| `dns.client.tls.certificate` | `DNS_CLIENT_TLS_CERTIFICATE`, `DNS_CLIENT_TLS_CERT` | Server certificate path/resource |
| `dns.client.tls.private-key` | `DNS_CLIENT_TLS_PRIVATE_KEY`, `DNS_CLIENT_TLS_KEY` | Server private key path/resource |
| `dns.client.tls.client-ca` | `DNS_CLIENT_TLS_CLIENT_CA` | Client CA path/resource; enables mTLS |

Plain HTTP:

```bash
./gradlew bootRun
```

HTTPS:

```bash
DNS_CLIENT_TLS_CERT=/run/tls/server.crt \
DNS_CLIENT_TLS_KEY=/run/tls/server.key \
./gradlew bootRun
```

mTLS:

```bash
DNS_CLIENT_TLS_CERT=/run/tls/server.crt \
DNS_CLIENT_TLS_KEY=/run/tls/server.key \
DNS_CLIENT_TLS_CLIENT_CA=/run/tls/client-ca.crt \
./gradlew bootRun
```

Docker can publish a different host port while the app still listens on container port
`8001`:

```bash
docker run -p 8080:8001 \
  -e DNS_CLIENT_TLS_CERT=/run/tls/server.crt \
  -e DNS_CLIENT_TLS_KEY=/run/tls/server.key \
  -e DNS_CLIENT_TLS_CLIENT_CA=/run/tls/client-ca.crt \
  -v /host/tls:/run/tls:ro \
  europe-docker.pkg.dev/dnsmonitor/containers/dns-client
```

Absolute filesystem paths are converted to `file:` resource URLs automatically. Relative
paths, `classpath:`, explicit `file:` URLs, and `https:` URLs are passed through to Spring
Boot SSL bundles. Plain `http:` URLs are rejected for TLS resources. TLS files are loaded
at startup, so rotating the certificate, private key, or client CA on disk requires
restarting the app/container.

When TLS is configured, the app checks the server certificate expiry, and the client CA
expiry when present, immediately and then every 5 minutes using a daemon scheduled
executor named `dns-client-cert-expiry-monitor`. If any checked certificate expires
within 10 minutes of the check time, the process exits with a non-zero status so the
container supervisor can restart it and load fresh TLS material.

## Tech stack

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 4.x |
| DNS resolution | [dnsjava](https://github.com/dnsjava/dnsjava) |
| Observability | Micrometer Tracing (OpenTelemetry) |
| Container image | [Jib](https://github.com/GoogleContainerTools/jib) → `bellsoft/liberica-runtime-container:jre-25-glibc` |
| Build | Gradle 9, Java 25 |

## Container image

Images are published to Google Artifact Registry at:

```
europe-docker.pkg.dev/dnsmonitor/containers/dns-client
```

Building and publishing is handled by the CI/CD pipeline on tagged releases.

## Development

### Project structure

```
src/main/java/io/dnsmonitor/dns/client/
├── DnsClientApplication.java       # Spring Boot entry point
├── DnsClientController.java        # REST endpoints
├── DnsClientWorker.java            # Parallel DNS lookup orchestration
├── DnsClientReply.java             # Response model
├── DnsLookupCommand.java           # Single lookup command
├── dnsjava/
│   └── DnsJavaAdapter.java         # dnsjava integration
├── model/                          # DNS record POJOs (A, AAAA, MX, …)
├── transform/
│   └── ModelTransformer.java       # Maps dnsjava types to model POJOs
└── validators/
    ├── Domain.java                 # @Domain constraint annotation
    └── DnsRecordType.java          # @DnsRecordType constraint annotation
```

### Adding a new record type

1. Add the type string to `DnsClientWorker.RECORD_TYPES`
2. Add a mapping in `DnsJavaAdapter.convertRecordType()`
3. Create a new POJO in `src/main/java/io/dnsmonitor/dns/client/model/`
4. Add a mapping case in `ModelTransformer`
5. Add a test in `DnsJavaAdapterTest`

### Container integration test

`scripts/integration-test.sh` builds the Jib image to a tar, loads it into podman or docker,
and tests the running container end to end. It checks:

- `/actuator/health` reports `UP`
- all 9 record types on `example.com`, plus a few extra lookups, against DNS-over-HTTPS
- bad input returns 400
- the container shuts down gracefully on SIGTERM

With `--tls` it also tests HTTPS, mTLS and the certificate expiry monitor. It uses throwaway
certificates that it generates with `openssl` in a temp directory. It cleans up the
containers, the network and the image it built, and exits non-zero if any check fails.

```bash
# Build the image and test it (needs Java 25, podman or docker, curl, python3)
scripts/integration-test.sh

# Add the HTTPS, mTLS and certificate-expiry checks (needs openssl)
scripts/integration-test.sh --tls

# Test an image you already have, without the DoH cross-check (offline)
scripts/integration-test.sh --image europe-docker.pkg.dev/dnsmonitor/containers/dns-client:latest --skip-doh

# On hosts that intercept DNS on port 53, send the app's lookups through a CoreDNS
# DNS-over-TLS forwarder container
scripts/integration-test.sh --dot --tls
```

The engine is detected automatically. Set `CONTAINER_ENGINE=docker` or `CONTAINER_ENGINE=podman`
to choose one. Run `scripts/integration-test.sh --help` for every option.

## License

See [LICENSE](LICENSE).
