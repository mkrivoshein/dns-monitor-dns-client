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

**Prerequisites:** Java 21 (Temurin)

```bash
./gradlew bootRun
```

The service listens on port **8001**.

```bash
./gradlew build   # compile + test
./gradlew test    # tests only
```

## Tech stack

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 3.x |
| DNS resolution | [dnsjava](https://github.com/dnsjava/dnsjava) |
| Observability | Micrometer Tracing (OpenTelemetry) |
| Container image | [Jib](https://github.com/GoogleContainerTools/jib) → `eclipse-temurin:21-noble` |
| Build | Gradle 8, Java 21 |

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

## License

See [LICENSE](LICENSE).
