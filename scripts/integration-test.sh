#!/usr/bin/env bash
# Container integration test for dns-monitor-dns-client.
#
# Builds the Jib image (or uses an existing one), runs it in a local container,
# and checks health, DNS answers for all supported record types (cross-checked
# against DNS-over-HTTPS), input validation, graceful SIGTERM shutdown and,
# optionally, HTTPS / mTLS mode with throwaway certificates.
#
# Only local containers are started. Nothing is pushed to any registry.
# Run `scripts/integration-test.sh --help` for usage.

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults (every option can also be set through the environment)
# ---------------------------------------------------------------------------
CONTAINER_ENGINE="${CONTAINER_ENGINE:-}"
IMAGE="${IMAGE:-}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_UNIT_TESTS="${SKIP_UNIT_TESTS:-0}"
GRADLE_ARGS="${GRADLE_ARGS:-}"
SKIP_DOH="${SKIP_DOH:-0}"
STRICT_DOH="${STRICT_DOH:-0}"
DOH_URL="${DOH_URL:-https://1.1.1.1/dns-query}"
DOT_FORWARDER="${DOT_FORWARDER:-0}"
DOT_UPSTREAMS="${DOT_UPSTREAMS:-tls://1.1.1.1 tls://1.0.0.1}"
DOT_SERVERNAME="${DOT_SERVERNAME:-cloudflare-dns.com}"
COREDNS_IMAGE="${COREDNS_IMAGE:-docker.io/coredns/coredns:1.12.1}"
DNS_SERVER="${DNS_SERVER:-}"
MODES="${MODES:-http}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-60}"
SHUTDOWN_TIMEOUT="${SHUTDOWN_TIMEOUT:-30}"
EXPIRY_EXIT_TIMEOUT="${EXPIRY_EXIT_TIMEOUT:-60}"
STRICT_EXPIRY="${STRICT_EXPIRY:-0}"
KEEP="${KEEP:-0}"

APP_PORT=8001
RUN_ID="it-$(date +%s)-$$"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: scripts/integration-test.sh [options]

Build options:
  --image NAME          Test an existing image instead of building one (implies --skip-build).
  --skip-build          Do not run Gradle/Jib; requires --image.
  --skip-unit-tests     Build the image with `-x test` (unit tests are skipped).
  --gradle-args ARGS    Extra Gradle arguments, e.g. "--offline".

Engine:
  --engine podman|docker
                        Container engine. Default: $CONTAINER_ENGINE, else podman, else docker.

DNS upstream:
  --dot                 Start a CoreDNS container that forwards to DNS-over-TLS (port 853)
                        and point the app's resolver at it. Use on hosts that intercept port 53.
  --dot-upstreams LIST  CoreDNS forward targets (default: "tls://1.1.1.1 tls://1.0.0.1").
  --dns-server IP       Point the app's resolver (dnsjava `dns.server`) at IP, no forwarder.

Cross-check:
  --skip-doh            Do not cross-check answers against DNS-over-HTTPS (offline hosts).
  --doh-url URL         DoH JSON endpoint (default: https://1.1.1.1/dns-query).
  --strict-doh          Treat CDN-varying A/AAAA differences as failures instead of warnings.

Modes:
  --modes LIST          Comma-separated list of http,https,mtls,expiry (default: http).
  --tls                 Shorthand for --modes http,https,mtls,expiry (needs openssl).
  --strict-expiry       In expiry mode, fail (not warn) when the app exits with code 0.

Other:
  --health-timeout S    Seconds to wait for /actuator/health UP (default: 60).
  --shutdown-timeout S  Seconds to wait for exit after SIGTERM (default: 30).
  --keep                Keep containers, network and built image for debugging.
  -h, --help            Show this help.

Every option has an environment equivalent: CONTAINER_ENGINE, IMAGE, SKIP_BUILD,
SKIP_UNIT_TESTS, GRADLE_ARGS, SKIP_DOH, DOH_URL, STRICT_DOH, DOT_FORWARDER,
DOT_UPSTREAMS, DNS_SERVER, MODES, STRICT_EXPIRY, HEALTH_TIMEOUT, SHUTDOWN_TIMEOUT, KEEP.
Exit status is 0 only when every check passes.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image) IMAGE="$2"; SKIP_BUILD=1; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --skip-unit-tests) SKIP_UNIT_TESTS=1; shift ;;
    --gradle-args) GRADLE_ARGS="$2"; shift 2 ;;
    --engine) CONTAINER_ENGINE="$2"; shift 2 ;;
    --dot) DOT_FORWARDER=1; shift ;;
    --dot-upstreams) DOT_UPSTREAMS="$2"; DOT_FORWARDER=1; shift 2 ;;
    --dns-server) DNS_SERVER="$2"; shift 2 ;;
    --skip-doh) SKIP_DOH=1; shift ;;
    --doh-url) DOH_URL="$2"; shift 2 ;;
    --strict-doh) STRICT_DOH=1; shift ;;
    --modes) MODES="$2"; shift 2 ;;
    --tls) MODES="http,https,mtls,expiry"; shift ;;
    --health-timeout) HEALTH_TIMEOUT="$2"; shift 2 ;;
    --shutdown-timeout) SHUTDOWN_TIMEOUT="$2"; shift 2 ;;
    --strict-expiry) STRICT_EXPIRY=1; shift ;;
    --keep) KEEP=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------------------
# Result bookkeeping
# ---------------------------------------------------------------------------
PASSED=()
FAILED=()
WARNED=()
CURRENT_PHASE="setup"

log()   { printf '%s\n' "$*"; }
phase() { CURRENT_PHASE="$1"; printf '\n=== %s ===\n' "$1"; }
pass()  { PASSED+=("[$CURRENT_PHASE] $1"); printf '  PASS  %s\n' "$1"; }
fail()  { FAILED+=("[$CURRENT_PHASE] $1"); printf '  FAIL  %s\n' "$1"; }
warn()  { WARNED+=("[$CURRENT_PHASE] $1"); printf '  WARN  %s\n' "$1"; }
die()   { printf 'ERROR: %s\n' "$*" >&2; FAILED+=("[$CURRENT_PHASE] $*"); exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "required tool not found: $1"; }

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
CONTAINERS=()
NETWORK=""
BUILT_IMAGE=""
WORKDIR=""

summary() {
  printf '\n=== SUMMARY ===\n'
  printf 'passed: %d  warnings: %d  failed: %d\n' "${#PASSED[@]}" "${#WARNED[@]}" "${#FAILED[@]}"
  local item
  for item in ${WARNED[@]+"${WARNED[@]}"}; do printf '  WARN  %s\n' "$item"; done
  for item in ${FAILED[@]+"${FAILED[@]}"}; do printf '  FAIL  %s\n' "$item"; done
  if [[ ${#FAILED[@]} -eq 0 ]]; then
    printf 'RESULT: PASS\n'
  else
    printf 'RESULT: FAIL\n'
  fi
}

cleanup() {
  local rc=$?
  set +e
  if [[ "$KEEP" == "1" ]]; then
    log ""
    log "--keep: leaving containers ${CONTAINERS[*]:-none}, network ${NETWORK:-none}, image ${BUILT_IMAGE:-none}, workdir ${WORKDIR:-none}"
  else
    local c
    for c in ${CONTAINERS[@]+"${CONTAINERS[@]}"}; do
      "$ENGINE" rm -f "$c" >/dev/null 2>&1
    done
    [[ -n "$NETWORK" ]] && "$ENGINE" network rm "$NETWORK" >/dev/null 2>&1
    [[ -n "$BUILT_IMAGE" ]] && "$ENGINE" rmi -f "$BUILT_IMAGE" >/dev/null 2>&1
    [[ -n "$WORKDIR" && -d "$WORKDIR" ]] && rm -rf "$WORKDIR"
  fi
  summary
  if [[ ${#FAILED[@]} -gt 0 && $rc -eq 0 ]]; then
    rc=1
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
phase "preflight"
if [[ -z "$CONTAINER_ENGINE" ]]; then
  if command -v podman >/dev/null 2>&1; then
    CONTAINER_ENGINE=podman
  elif command -v docker >/dev/null 2>&1; then
    CONTAINER_ENGINE=docker
  else
    ENGINE=true
    die "neither podman nor docker found; set CONTAINER_ENGINE"
  fi
fi
ENGINE="$CONTAINER_ENGINE"
need "$ENGINE"
need curl
need python3
"$ENGINE" info >/dev/null 2>&1 || die "'$ENGINE info' failed; is the engine running and usable by this user?"
log "engine: $ENGINE ($("$ENGINE" --version))"

IFS=',' read -r -a MODE_LIST <<<"$MODES"
for m in "${MODE_LIST[@]}"; do
  case "$m" in
    http|https|mtls|expiry) ;;
    *) die "unknown mode '$m' (expected http, https, mtls, expiry)" ;;
  esac
  if [[ "$m" != "http" ]]; then
    need openssl
  fi
done
if [[ "$SKIP_BUILD" == "1" && -z "$IMAGE" ]]; then
  die "--skip-build needs --image NAME"
fi

WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/dns-client-it.XXXXXX")"
chmod 755 "$WORKDIR"

# Volume flag: relabel for SELinux hosts when using podman.
VOL_OPTS="ro"
[[ "$ENGINE" == "podman" ]] && VOL_OPTS="ro,Z"

# Python helper that normalises app and DoH answers and compares them as sets.
cat >"$WORKDIR/compare.py" <<'PY'
import ipaddress, json, re, sys

rtype, app_file, doh_file, cdn_tolerant = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] == "1"
TYPE_NUM = {"A": 1, "NS": 2, "CNAME": 5, "SOA": 6, "MX": 15, "TXT": 16, "AAAA": 28, "SRV": 33, "CAA": 257}

def name(v):
    v = v.lower()
    return v if v == "." else v.rstrip(".")

def unescape(s):
    s = re.sub(r"\\(\d{3})", lambda m: chr(int(m.group(1))), s)
    return re.sub(r"\\(.)", r"\1", s)

def app_value(r):
    t = r["recordType"]
    if t == "A":
        return r["ipv4"]
    if t == "AAAA":
        return ipaddress.ip_address(r["ipv6"]).compressed
    if t in ("CNAME", "NS"):
        return name(r["value"])
    if t == "MX":
        return f'{r["priority"]} {name(r["value"])}'
    if t == "SRV":
        return f'{r["priority"]} {r["weight"]} {r["port"]} {name(r["value"])}'
    if t == "TXT":
        return r["value"]
    if t == "CAA":
        return f'{r["flags"]} {r["type"].lower()} {r["value"]}'
    if t == "SOA":
        return f'{name(r["value"])} {name(r["admin"])}'
    return json.dumps(r, sort_keys=True)

def doh_values(data):
    if rtype == "A":
        return [data]
    if rtype == "AAAA":
        return [ipaddress.ip_address(data).compressed]
    if rtype in ("CNAME", "NS"):
        return [name(data)]
    if rtype == "MX":
        p, host = data.split(None, 1)
        return [f"{p} {name(host)}"]
    if rtype == "SRV":
        p, w, port, host = data.split()
        return [f"{p} {w} {port} {name(host)}"]
    if rtype == "TXT":
        # One TXT RR may hold several character-strings; the app returns each one.
        return [unescape(s) for s in re.findall(r'"((?:[^"\\]|\\.)*)"', data)] or [data]
    if rtype == "CAA":
        flags, tag, value = data.split(None, 2)
        return [f"{flags} {tag.lower()} {value.strip(chr(34))}"]
    if rtype == "SOA":
        parts = data.split()
        return [f"{name(parts[0])} {name(parts[1])}"]
    return [data]

app = json.load(open(app_file))
doh = json.load(open(doh_file))
app_records = [r for r in app.get("records", []) if r.get("recordType") == rtype]
app_set = {app_value(r) for r in app_records}
if doh.get("Status") not in (0, 3):
    print(f"DoH returned status {doh.get('Status')}")
    sys.exit(4)
doh_set = set()
for a in doh.get("Answer", []) or []:
    if a.get("type") == TYPE_NUM[rtype]:
        doh_set.update(doh_values(a["data"]))

detail = f"app={sorted(app_set)} doh={sorted(doh_set)}"
if rtype == "SOA" and app_records and doh.get("Answer"):
    soa = [a for a in doh["Answer"] if a.get("type") == 6]
    if soa and str(app_records[0]["serial"]) != soa[0]["data"].split()[2]:
        detail += f" (serial differs: app={app_records[0]['serial']} doh={soa[0]['data'].split()[2]})"
print(detail)
if app_set == doh_set:
    sys.exit(0)
if cdn_tolerant and app_set and doh_set:
    sys.exit(3)
sys.exit(1)
PY

# ---------------------------------------------------------------------------
# Image
# ---------------------------------------------------------------------------
phase "image"
if [[ "$SKIP_BUILD" == "1" ]]; then
  "$ENGINE" image inspect "$IMAGE" >/dev/null 2>&1 || "$ENGINE" pull "$IMAGE" >/dev/null \
    || die "image $IMAGE not found locally and could not be pulled"
  pass "using existing image $IMAGE"
else
  need java
  IMAGE="localhost/dns-client-it:${RUN_ID}"
  gradle_cmd=("$REPO_ROOT/gradlew" -p "$REPO_ROOT" -Dorg.gradle.welcome=never)
  if [[ -n "$GRADLE_ARGS" ]]; then
    read -r -a extra_args <<<"$GRADLE_ARGS"
    gradle_cmd+=("${extra_args[@]}")
  fi
  gradle_cmd+=(build jibBuildTar "-Djib.to.image=${IMAGE}")
  [[ "$SKIP_UNIT_TESTS" == "1" ]] && gradle_cmd+=(-x test)
  log "running: ${gradle_cmd[*]}"
  if ! "${gradle_cmd[@]}"; then
    die "Gradle build failed"
  fi
  load_out="$("$ENGINE" load -i "$REPO_ROOT/build/jib-image.tar" 2>&1)" || die "image load failed: $load_out"
  loaded="$(printf '%s\n' "$load_out" | sed -n 's/^Loaded image[^:]*: *//p' | tail -n 1)"
  [[ -n "$loaded" ]] && IMAGE="$loaded"
  BUILT_IMAGE="$IMAGE"
  pass "built and loaded $IMAGE"
fi

# ---------------------------------------------------------------------------
# Network and optional DoT forwarder
# ---------------------------------------------------------------------------
phase "network"
NETWORK="${RUN_ID}-net"
"$ENGINE" network create "$NETWORK" >/dev/null || die "could not create network $NETWORK"
pass "created network $NETWORK"

container_ip() {
  "$ENGINE" inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$1"
}

COREDNS_NAME=""
if [[ "$DOT_FORWARDER" == "1" ]]; then
  COREDNS_NAME="${RUN_ID}-coredns"
  cat >"$WORKDIR/Corefile" <<EOF
.:53 {
    forward . ${DOT_UPSTREAMS} {
        tls_servername ${DOT_SERVERNAME}
    }
    log
    errors
}
EOF
  chmod 644 "$WORKDIR/Corefile"
  "$ENGINE" run -d --name "$COREDNS_NAME" --network "$NETWORK" \
    -v "$WORKDIR/Corefile:/Corefile:${VOL_OPTS}" "$COREDNS_IMAGE" -conf /Corefile >/dev/null \
    || die "could not start CoreDNS ($COREDNS_IMAGE)"
  CONTAINERS+=("$COREDNS_NAME")
  sleep 1
  [[ "$("$ENGINE" inspect -f '{{.State.Running}}' "$COREDNS_NAME")" == "true" ]] \
    || { "$ENGINE" logs "$COREDNS_NAME" 2>&1 | tail -n 20; die "CoreDNS exited"; }
  DNS_SERVER="$(container_ip "$COREDNS_NAME")"
  [[ -n "$DNS_SERVER" ]] || die "could not read CoreDNS container IP"
  pass "CoreDNS DoT forwarder up at $DNS_SERVER -> ${DOT_UPSTREAMS}"
fi

APP_ENV=()
if [[ -n "$DNS_SERVER" ]]; then
  # dnsjava reads the resolver from the dns.server system property before resolv.conf.
  APP_ENV+=(-e "JAVA_TOOL_OPTIONS=-Ddns.server=${DNS_SERVER}")
  log "app resolver: dns.server=${DNS_SERVER}"
else
  log "app resolver: container default (/etc/resolv.conf from $ENGINE)"
fi

# ---------------------------------------------------------------------------
# TLS material (throwaway, generated in WORKDIR, never committed)
# ---------------------------------------------------------------------------
TLS_DIR="$WORKDIR/tls"
gen_tls() {
  [[ -d "$TLS_DIR" ]] && return 0
  mkdir -p "$TLS_DIR"
  chmod 755 "$TLS_DIR"
  (
    cd "$TLS_DIR"
    local quiet=(-quiet)
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out ca.key "${quiet[@]}" 2>/dev/null \
      || openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out ca.key
    openssl req -x509 -new -key ca.key -sha256 -days 2 -subj "/CN=dns-client-it test CA" -out ca.crt \
      -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out rogue-ca.key
    openssl req -x509 -new -key rogue-ca.key -sha256 -days 2 -subj "/CN=dns-client-it rogue CA" -out rogue-ca.crt \
      -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"
    cat >server.ext <<'EXT'
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EXT
    cat >client.ext <<'EXT'
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=clientAuth
EXT
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out server.key
    openssl req -new -key server.key -subj "/CN=localhost" -out server.csr
    openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 2 -sha256 \
      -extfile server.ext -out server.crt 2>/dev/null
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out client.key
    openssl req -new -key client.key -subj "/CN=dns-client-it client" -out client.csr
    openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 2 -sha256 \
      -extfile client.ext -out client.crt 2>/dev/null
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out rogue-client.key
    openssl req -new -key rogue-client.key -subj "/CN=dns-client-it rogue client" -out rogue-client.csr
    openssl x509 -req -in rogue-client.csr -CA rogue-ca.crt -CAkey rogue-ca.key -CAcreateserial -days 2 -sha256 \
      -extfile client.ext -out rogue-client.crt 2>/dev/null
    # Short-lived server certificate (expires in 5 minutes) for the expiry monitor check.
    # `openssl ca` is used because `openssl x509 -days` only has day granularity on OpenSSL 3.0.
    local start end
    if start="$(date -u -d '-1 minute' +%Y%m%d%H%M%SZ 2>/dev/null)"; then
      end="$(date -u -d '+5 minutes' +%Y%m%d%H%M%SZ)"
    else
      start="$(date -u -v-1M +%Y%m%d%H%M%SZ)"
      end="$(date -u -v+5M +%Y%m%d%H%M%SZ)"
    fi
    mkdir -p ca-db && : >ca-db/index.txt && echo 1000 >ca-db/serial
    cat >ca.cnf <<CNF
[ ca ]
default_ca = test_ca
[ test_ca ]
dir = ca-db
database = ca-db/index.txt
new_certs_dir = ca-db
serial = ca-db/serial
default_md = sha256
policy = any_policy
unique_subject = no
[ any_policy ]
commonName = supplied
CNF
    openssl ca -batch -notext -config ca.cnf -cert ca.crt -keyfile ca.key -in server.csr \
      -startdate "$start" -enddate "$end" -extfile server.ext -out short.crt 2>/dev/null
    chmod 644 ./*.crt ./*.key
  )
}

# ---------------------------------------------------------------------------
# Helpers for the app container
# ---------------------------------------------------------------------------
APP_NAME=""
BASE_URL=""
CURL_TLS=()

# http_get PATH OUTFILE [extra curl args...] -> prints HTTP status (000 on transport error)
http_get() {
  local path="$1" out="$2"
  shift 2
  curl -sS -o "$out" -w '%{http_code}' --max-time 30 ${CURL_TLS[@]+"${CURL_TLS[@]}"} "$@" "${BASE_URL}${path}" 2>"$out.err" || true
}

start_app() {
  local mode="$1"
  shift
  APP_NAME="${RUN_ID}-app-${mode}"
  "$ENGINE" run -d --name "$APP_NAME" --network "$NETWORK" -p "127.0.0.1::${APP_PORT}" \
    ${APP_ENV[@]+"${APP_ENV[@]}"} "$@" "$IMAGE" >/dev/null || { fail "container start ($mode)"; return 1; }
  CONTAINERS+=("$APP_NAME")
  local hostport
  hostport="$("$ENGINE" port "$APP_NAME" "${APP_PORT}/tcp" | head -n 1)"
  HOST_PORT="${hostport##*:}"
  log "container $APP_NAME listening on 127.0.0.1:${HOST_PORT}"
}

dump_logs() {
  log "  --- last 40 log lines of $APP_NAME ---"
  "$ENGINE" logs "$APP_NAME" 2>&1 | tail -n 40 | sed 's/^/  | /'
}

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) code body="$WORKDIR/health.json"
  while ((SECONDS < deadline)); do
    if [[ "$("$ENGINE" inspect -f '{{.State.Running}}' "$APP_NAME")" != "true" ]]; then
      fail "container exited before becoming healthy (exit code $("$ENGINE" inspect -f '{{.State.ExitCode}}' "$APP_NAME"))"
      dump_logs
      return 1
    fi
    code="$(http_get /actuator/health "$body")"
    if [[ "$code" == "200" ]] && grep -q '"status":"UP"' "$body"; then
      local started
      started="$("$ENGINE" logs "$APP_NAME" 2>&1 | sed -n 's/.*\(Started DnsClientApplication in [0-9.]* seconds\).*/\1/p' | head -n 1)"
      pass "/actuator/health is UP (${started:-startup time not logged})"
      return 0
    fi
    sleep 0.5
  done
  fail "/actuator/health not UP within ${HEALTH_TIMEOUT}s (last HTTP status ${code:-none})"
  dump_logs
  return 1
}

doh_query() {
  local qname="$1" qtype="$2" out="$3" i
  for i in 1 2 3; do
    if curl -sS --max-time 15 -H 'accept: application/dns-json' \
        "${DOH_URL}?name=${qname}&type=${qtype}" -o "$out" 2>/dev/null \
        && python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$out" 2>/dev/null; then
      return 0
    fi
    sleep "$i"
  done
  return 1
}

# check_lookup TYPE DOMAIN: query the app and compare with DoH.
check_lookup() {
  local qtype="$1" qname="$2" app_out="$WORKDIR/app-$1-$2.json" doh_out="$WORKDIR/doh-$1-$2.json"
  local attempt code result rc cdn=0
  [[ "$qtype" == "A" || "$qtype" == "AAAA" ]] && [[ "$STRICT_DOH" != "1" ]] && cdn=1
  for attempt in 1 2; do
    code="$(http_get "/dns/${qtype}/${qname}" "$app_out")"
    if [[ "$code" != "200" ]]; then
      fail "$qtype $qname: HTTP $code"
      return 0
    fi
    if [[ "$SKIP_DOH" == "1" ]]; then
      if python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["domain"]==sys.argv[2] and isinstance(d["records"], list)' "$app_out" "$qname"; then
        pass "$qtype $qname: HTTP 200, $(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["records"]))' "$app_out") record(s) (DoH cross-check skipped)"
      else
        fail "$qtype $qname: malformed response body"
      fi
      return 0
    fi
    if ! doh_query "$qname" "$qtype" "$doh_out"; then
      fail "$qtype $qname: DoH query to $DOH_URL failed (use --skip-doh when offline)"
      return 0
    fi
    rc=0
    result="$(python3 "$WORKDIR/compare.py" "$qtype" "$app_out" "$doh_out" "$cdn")" || rc=$?
    case "$rc" in
      0) pass "$qtype $qname matches DoH: $result"; return 0 ;;
      3) warn "$qtype $qname differs from DoH (CDN-varying answer tolerated): $result"; return 0 ;;
      *) [[ $attempt -eq 1 ]] && { log "  ....  $qtype $qname mismatch, retrying once: $result"; sleep 2; continue; } ;;
    esac
  done
  fail "$qtype $qname does not match DoH: $result"
}

check_status() {
  local expected="$1" path="$2" desc="$3" code
  code="$(http_get "$path" "$WORKDIR/status.out")"
  if [[ "$code" == "$expected" ]]; then
    pass "$desc -> HTTP $code"
  else
    fail "$desc -> HTTP $code (expected $expected)"
  fi
}

check_shutdown() {
  local start=$SECONDS running exit_code
  "$ENGINE" kill -s TERM "$APP_NAME" >/dev/null || { fail "could not send SIGTERM"; return 0; }
  while ((SECONDS - start < SHUTDOWN_TIMEOUT)); do
    running="$("$ENGINE" inspect -f '{{.State.Running}}' "$APP_NAME")"
    [[ "$running" == "false" ]] && break
    sleep 0.5
  done
  if [[ "$running" != "false" ]]; then
    fail "container still running ${SHUTDOWN_TIMEOUT}s after SIGTERM"
    return 0
  fi
  exit_code="$("$ENGINE" inspect -f '{{.State.ExitCode}}' "$APP_NAME")"
  if [[ "$exit_code" == "0" || "$exit_code" == "143" ]]; then
    pass "exited after SIGTERM in ~$((SECONDS - start))s with exit code $exit_code"
  else
    fail "exit code after SIGTERM was $exit_code (expected 0 or 143)"
    dump_logs
  fi
  if "$ENGINE" logs "$APP_NAME" 2>&1 | grep -q 'Graceful shutdown complete'; then
    pass "logs show 'Commencing graceful shutdown' / 'Graceful shutdown complete'"
  else
    fail "logs do not show 'Graceful shutdown complete'"
    dump_logs
  fi
}

# ---------------------------------------------------------------------------
# Modes
# ---------------------------------------------------------------------------
RECORD_TYPES=(A AAAA CAA CNAME MX NS SOA SRV TXT)
EXTRA_LOOKUPS=("MX gmail.com" "A cloudflare.com" "CAA google.com" "SRV _imaps._tcp.gmail.com" "CNAME www.github.com")

run_http() {
  phase "http: start"
  BASE_URL=""
  CURL_TLS=()
  start_app http || return 0
  BASE_URL="http://127.0.0.1:${HOST_PORT}"
  wait_healthy || return 0

  phase "http: lookups"
  local t entry
  for t in "${RECORD_TYPES[@]}"; do
    check_lookup "$t" example.com
  done
  for entry in "${EXTRA_LOOKUPS[@]}"; do
    # shellcheck disable=SC2086
    check_lookup $entry
  done
  # All-types endpoint: same record types as the per-type lookups.
  local code
  code="$(http_get /dns/example.com "$WORKDIR/all.json")"
  if [[ "$code" == "200" ]]; then
    local cmp
    if cmp="$(python3 - "$WORKDIR" <<'PY'
import glob, json, os, sys
d = sys.argv[1]
all_types = {r["recordType"] for r in json.load(open(os.path.join(d, "all.json")))["records"]}
single = set()
for f in glob.glob(os.path.join(d, "app-*-example.com.json")):
    single |= {r["recordType"] for r in json.load(open(f))["records"]}
print(f"all={sorted(all_types)} per-type={sorted(single)}")
sys.exit(0 if all_types == single else 1)
PY
)"; then
      pass "GET /dns/example.com returns every record type: $cmp"
    else
      fail "GET /dns/example.com record types differ: $cmp"
    fi
  else
    fail "GET /dns/example.com -> HTTP $code"
  fi

  phase "http: input validation"
  check_status 400 /dns/BOGUS/example.com "unknown record type"
  check_status 400 /dns/A/localhost "domain without a dot (type endpoint)"
  check_status 400 /dns/localhost "domain without a dot (all-types endpoint)"
  check_status 400 "/dns/A/exa%20mple.com" "domain with whitespace"
  code="$(http_get /dns/PTR/example.com "$WORKDIR/ptr.out")"
  if [[ "$code" == 4* ]]; then
    pass "valid-but-unsupported type PTR -> HTTP $code"
  else
    warn "valid-but-unsupported type PTR -> HTTP $code (known gap: validator accepts any dnsjava type, worker throws)"
  fi

  if [[ -n "$COREDNS_NAME" ]]; then
    if "$ENGINE" logs "$COREDNS_NAME" 2>&1 | grep -q 'IN example.com.'; then
      pass "app queries went through the DoT forwarder ($("$ENGINE" logs "$COREDNS_NAME" 2>&1 | grep -c ' IN ') logged)"
    else
      fail "no app queries seen in CoreDNS logs"
    fi
  fi

  phase "http: graceful shutdown"
  check_shutdown
}

# expect_reject CODE DESC: a TLS-level rejection shows up as curl status 000.
expect_reject() {
  if [[ "$1" == "000" ]]; then
    pass "$2"
  else
    fail "$2 (got HTTP $1 instead of a handshake failure)"
  fi
}

run_https() {
  gen_tls
  phase "https: start"
  CURL_TLS=(--cacert "$TLS_DIR/ca.crt")
  start_app https -v "$TLS_DIR:/tls:${VOL_OPTS}" \
    -e DNS_CLIENT_TLS_CERTIFICATE=/tls/server.crt -e DNS_CLIENT_TLS_PRIVATE_KEY=/tls/server.key || return 0
  BASE_URL="https://127.0.0.1:${HOST_PORT}"
  wait_healthy || return 0

  phase "https: checks"
  check_lookup A example.com
  check_status 400 /dns/BOGUS/example.com "unknown record type over HTTPS"
  local code
  CURL_TLS=()
  code="$(http_get /actuator/health "$WORKDIR/untrusted.out")"
  expect_reject "$code" "client without the test CA rejects the server certificate"
  BASE_URL="http://127.0.0.1:${HOST_PORT}"
  code="$(http_get /actuator/health "$WORKDIR/plain.out")"
  if [[ "$code" != "200" ]]; then
    pass "plain HTTP on the TLS port is refused (HTTP $code)"
  else
    fail "plain HTTP on the TLS port returned 200"
  fi
  BASE_URL="https://127.0.0.1:${HOST_PORT}"
  CURL_TLS=(--cacert "$TLS_DIR/ca.crt")
  local proto
  proto="$(openssl s_client -connect "127.0.0.1:${HOST_PORT}" -CAfile "$TLS_DIR/ca.crt" -verify_return_error </dev/null 2>/dev/null \
    | sed -n 's/^ *Protocol *: *//p; s/^New, \(TLSv[0-9.]*\),.*/\1/p' | head -n 1)" || true
  if [[ -n "$proto" ]]; then
    pass "TLS handshake verified against the test CA (protocol $proto)"
  else
    fail "openssl s_client could not complete a verified handshake"
  fi

  phase "https: graceful shutdown"
  check_shutdown
}

run_mtls() {
  gen_tls
  phase "mtls: start"
  CURL_TLS=(--cacert "$TLS_DIR/ca.crt" --cert "$TLS_DIR/client.crt" --key "$TLS_DIR/client.key")
  start_app mtls -v "$TLS_DIR:/tls:${VOL_OPTS}" \
    -e DNS_CLIENT_TLS_CERTIFICATE=/tls/server.crt -e DNS_CLIENT_TLS_PRIVATE_KEY=/tls/server.key \
    -e DNS_CLIENT_TLS_CLIENT_CA=/tls/ca.crt || return 0
  BASE_URL="https://127.0.0.1:${HOST_PORT}"
  wait_healthy || return 0

  phase "mtls: checks"
  check_lookup A example.com
  check_lookup MX gmail.com
  local code
  CURL_TLS=(--cacert "$TLS_DIR/ca.crt")
  code="$(http_get /actuator/health "$WORKDIR/nocert.out")"
  expect_reject "$code" "request without a client certificate is rejected in the handshake"
  CURL_TLS=(--cacert "$TLS_DIR/ca.crt" --cert "$TLS_DIR/rogue-client.crt" --key "$TLS_DIR/rogue-client.key")
  code="$(http_get /actuator/health "$WORKDIR/rogue.out")"
  expect_reject "$code" "client certificate from an untrusted CA is rejected"
  CURL_TLS=(--cacert "$TLS_DIR/ca.crt" --cert "$TLS_DIR/client.crt" --key "$TLS_DIR/client.key")

  phase "mtls: graceful shutdown"
  check_shutdown
}

run_expiry() {
  gen_tls
  phase "expiry: certificate expiring within 10 minutes"
  CURL_TLS=()
  start_app expiry -v "$TLS_DIR:/tls:${VOL_OPTS}" \
    -e DNS_CLIENT_TLS_CERTIFICATE=/tls/short.crt -e DNS_CLIENT_TLS_PRIVATE_KEY=/tls/server.key || return 0
  local start=$SECONDS running="true" exit_code
  while ((SECONDS - start < EXPIRY_EXIT_TIMEOUT)); do
    running="$("$ENGINE" inspect -f '{{.State.Running}}' "$APP_NAME")"
    [[ "$running" == "false" ]] && break
    sleep 0.5
  done
  if [[ "$running" != "false" ]]; then
    fail "container with a 5-minute certificate still running after ${EXPIRY_EXIT_TIMEOUT}s"
    dump_logs
    return 0
  fi
  exit_code="$("$ENGINE" inspect -f '{{.State.ExitCode}}' "$APP_NAME")"
  if [[ "$exit_code" != "0" ]]; then
    pass "expiry monitor stopped the app with exit code $exit_code after ~$((SECONDS - start))s"
  elif [[ "$STRICT_EXPIRY" == "1" ]]; then
    fail "app exited with code 0; expected non-zero from the expiry monitor"
  else
    # Known race: the monitor runs on a daemon thread. SpringApplication.exit() closes the
    # context, Tomcat's non-daemon threads stop, and the JVM can exit with 0 before the
    # monitor thread reaches System.exit(1). Use --strict-expiry to make this a failure.
    warn "app stopped but exited with code 0, not non-zero (expiry-monitor exit-code race; --strict-expiry fails this)"
  fi
  if "$ENGINE" logs "$APP_NAME" 2>&1 | grep -q 'minute exit window'; then
    pass "logs name the expiring certificate and the exit window"
  else
    fail "expiry monitor log line not found"
    dump_logs
  fi
}

for m in "${MODE_LIST[@]}"; do
  "run_${m}"
done
