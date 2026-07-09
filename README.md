# Vehicle Stream Prototype

A reference implementation of an event-driven vehicle telemetry pipeline using **Valkey streams** and a **key-value snapshot store**. A producer writes full vehicle JSON snapshots keyed by VIN and emits lightweight stream events that carry only the VIN; a consumer reads those events, looks up the snapshot, and logs the result.

The same Docker images run locally (Docker Compose + Valkey) and on AWS (ECS Fargate + MemoryDB) with **configuration-only** changes — including IAM-based authentication with no stored passwords.

## Architecture

```
┌─────────────┐   SET <VIN> <JSON>          ┌──────────────────────┐
│   Producer  │ ──────────────────────────▶ │  Valkey / MemoryDB   │
│  (simulated │   XADD vehicle:vin-events   │  • KV store (VIN→JSON)│
│   upstream) │   { vin: <VIN> }          │  • Stream (VIN events) │
└─────────────┘                           └──────────┬───────────┘
                                                     │
                                                     │ XREADGROUP
                                                     ▼
                                          ┌──────────────────────┐
                                          │      Consumer        │
                                          │  GET <VIN> → log JSON│
                                          │  XACK                │
                                          └──────────────────────┘
```

### Data flow

1. **Producer** builds a randomized vehicle snapshot and stores it with `SET <VIN> <JSON>` (TTL configurable).
2. **Producer** publishes a stream event with `XADD vehicle:vin-events vin=<VIN>` — the event carries **only the VIN**, not the full payload.
3. **Consumer** reads events via a **consumer group** (`XREADGROUP`), fetches the snapshot with `GET <VIN>`, logs it, and acknowledges with `XACK`.

This pattern decouples event notification from payload storage: stream entries stay small, snapshots can be large, and consumers always read the latest stored state for a VIN.

## Components

| Service | Role |
|---------|------|
| **`producer/`** | Simulated upstream data source. Emits a randomized snapshot every 2 seconds from a fixed fleet of VINs. Throwaway stand-in until real upstream access is available. |
| **`consumer/`** | The core deliverable. Durable stream consumer with consumer-group semantics, pending-entry recovery, and horizontal scaling support. |

## Tech stack

- **Java 21** with Maven (shaded fat JARs)
- **[Lettuce](https://github.com/redis/lettuce-core)** — Valkey/Redis client
- **[Valkey](https://valkey.io/)** 8 (local) / **AWS MemoryDB for Valkey** (production)
- **Docker** + **Docker Compose** for local development
- **AWS ECS Fargate**, **ECR**, **CloudWatch Logs**, **IAM auth** for cloud deployment

## Project structure

```
vehicle-stream-prototype/
├── docker-compose.yml          # Local stack: Valkey + producer + consumer
├── producer/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/ai/conexio/vehicle/producer/
│       ├── ProducerMain.java           # Snapshot generation + XADD
│       ├── ValkeyConfig.java           # Env-driven connection config
│       ├── IamAuthTokenGenerator.java  # MemoryDB SigV4 token signing
│       └── IamRedisCredentialsProvider.java
├── consumer/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/ai/conexio/vehicle/consumer/
│       ├── ConsumerMain.java           # XREADGROUP + GET + XACK + XAUTOCLAIM
│       ├── ValkeyConfig.java
│       ├── IamAuthTokenGenerator.java
│       └── IamRedisCredentialsProvider.java
└── PHASE2-CONSOLE-GUIDE.md     # Step-by-step AWS Console deployment guide
```

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (or Docker Engine + Compose) for local runs
- **Java 21** and **Maven 3.9+** (only if building outside Docker)

## Quick start (local)

```bash
git clone <your-repo-url>
cd vehicle-stream-prototype
docker compose up --build
```

Watch the `vehicle-consumer` container logs. You should see output like:

```
Snapshot for 1HGBH41JXMN109186 (stream id 1733...-0): {"vin":"1HGBH41JXMN109186","capturedAt":"2026-06-05T14:32:10.482Z","speedKph":62.4,"odometerKm":45231.2,...}
```

Stop with `Ctrl+C`, then tear down:

```bash
docker compose down
```

### Build without Docker Compose

```bash
# Terminal 1 — start Valkey
docker run -d --name valkey -p 6379:6379 valkey/valkey:8

# Terminal 2 — producer
cd producer && mvn -q clean package
VALKEY_HOST=localhost java -jar target/producer-1.0.0.jar

# Terminal 3 — consumer
cd consumer && mvn -q clean package
VALKEY_HOST=localhost java -jar target/consumer-1.0.0.jar
```

## Configuration

All settings are driven by environment variables — the same images work locally and on AWS.

| Variable | Default | Description |
|----------|---------|-------------|
| `VALKEY_HOST` | `localhost` | Valkey/MemoryDB hostname (`valkey` inside Compose) |
| `VALKEY_PORT` | `6379` | Port |
| `VALKEY_TLS` | `false` | Set `true` for MemoryDB (TLS required) |
| `VALKEY_AUTH_MODE` | `none` | `none` (local) or `iam` (MemoryDB IAM auth) |
| `VALKEY_USER` | — | MemoryDB user name (AUTH username + token `User` field) |
| `VALKEY_CLUSTER_NAME` | — | MemoryDB cluster **name** for IAM token signing (not the endpoint DNS) |
| `AWS_REGION` | `us-east-1` | Region for SigV4 signing (Fargate sets this automatically) |
| `STREAM_KEY` | `vehicle:vin-events` | Stream key |
| `PRODUCE_INTERVAL_MS` | `2000` | Producer emit interval (producer only) |
| `SNAPSHOT_TTL_SECONDS` | `3600` | TTL on snapshot keys (producer only) |
| `BLOCK_MS` | `5000` | Consumer `XREADGROUP` block window (consumer only) |
| `GROUP_NAME` | `vehicle-consumers` | Consumer group name (consumer only) |
| `CONSUMER_NAME` | hostname | Unique name within the group; defaults to task hostname on Fargate |
| `CLAIM_MIN_IDLE_MS` | `60000` | Idle time before `XAUTOCLAIM` reclaims stranded entries (consumer only) |

### IAM authentication (AWS)

With `VALKEY_AUTH_MODE=iam`, the app generates a short-lived SigV4 token from the task's IAM role on each connection — **no password is stored anywhere**. See `IamAuthTokenGenerator` for the signing logic.

Required IAM permission: `memorydb:connect` on the cluster and user ARNs.

## Delivery semantics

The consumer uses a **stream consumer group** (`XREADGROUP` + `XACK`), providing **at-least-once** delivery with no lost entries:

- The group is created at id `0`, so a consumer starting after the producer has already written still receives the full backlog.
- While the consumer is down, the producer keeps appending; on restart the group resumes from its tracked position.
- Each entry stays in the Pending Entries List (PEL) until `XACK`. On restart, the consumer first drains its own un-acked entries before reading new ones (`>`).

Because delivery is at-least-once, an entry may be processed more than once after a crash. This is safe here since `GET <VIN>` + log is idempotent.

### Scaling out

Run multiple consumer instances sharing the same `GROUP_NAME`. The server load-balances entries across consumers — each entry goes to exactly one instance. `CONSUMER_NAME` defaults to the hostname (unique per Fargate task), so scaling is as simple as increasing the ECS service desired count.

If an instance dies mid-work, its un-acked entries are reclaimed by live consumers via `XAUTOCLAIM` after `CLAIM_MIN_IDLE_MS` (default 60s).

> The stream is currently unbounded. To cap backlog size, add `MAXLEN ~ <N>` to the producer's `XADD` call.

## Deploy to AWS

The same images deploy to **ECS Fargate** against **AWS MemoryDB for Valkey** with environment-variable changes only:

1. Build and push both images to **ECR**.
2. Create a single-shard MemoryDB cluster (Valkey ≥ 7.0, TLS always on).
3. Create a MemoryDB user with **IAM** authentication and an IAM task role granting `memorydb:connect`.
4. Run Fargate tasks in the same VPC with security group access to port 6379.
5. Set `VALKEY_HOST`, `VALKEY_TLS=true`, `VALKEY_AUTH_MODE=iam`, `VALKEY_USER`, and `VALKEY_CLUSTER_NAME`.
6. Read output from **CloudWatch Logs**.

See **[PHASE2-CONSOLE-GUIDE.md](./PHASE2-CONSOLE-GUIDE.md)** for a full click-by-click walkthrough (ECR, MemoryDB, IAM, ECS task definitions, verification, and teardown).

## Example snapshot payload

```json
{
  "vin": "1HGBH41JXMN109186",
  "capturedAt": "2026-06-05T14:32:10.482Z",
  "odometerKm": 45231.2,
  "speedKph": 62.4,
  "engineRpm": 2100,
  "fuelLevelPct": 73.5,
  "coolantTempC": 88.2,
  "batteryVoltage": 13.1,
  "gear": "D",
  "ignitionOn": true,
  "location": { "lat": 17.385, "lon": 78.4867 }
}
```

## License

Prototype / internal reference implementation. Add a license file if you intend to open-source this publicly.
