# Vehicle Stream Prototype

End-to-end pipeline: a producer stores vehicle JSON snapshots in Valkey keyed by VIN and pushes stream events carrying **only the VIN**; a consumer reads those events, looks up the snapshot, and logs it.

The same Docker images run locally (Compose + Valkey) and on AWS (Fargate + MemoryDB) — config only, no code changes.

```
producer  --SET <VIN> <JSON>------------------>  Valkey / MemoryDB
          --XADD vehicle:vin-events vin=<VIN>-->     (KV + stream)
                                                          |
consumer  <--XREADGROUP (VIN)-----------------------------+
          --GET <VIN> --> log JSON  --XACK-->
```

- **`producer/`** — simulated upstream; emits a randomized snapshot every 2s
- **`consumer/`** — stream consumer with consumer-group semantics (`XREADGROUP`, `XACK`, `XAUTOCLAIM`)

## Quick start

Requires Docker.

```bash
docker compose up --build
```

Watch `vehicle-consumer` logs for lines like:

```
Snapshot for 1HGBH41JXMN109186 (stream id ...): {"vin":"1HGBH41JXMN109186","speedKph":62.4,...}
```

Stop with `Ctrl+C`, then `docker compose down`.

## Configuration

All settings via environment variables. Key ones:

| Variable | Default | Notes |
|----------|---------|-------|
| `VALKEY_HOST` | `localhost` | `valkey` inside Compose |
| `VALKEY_TLS` | `false` | `true` for MemoryDB |
| `VALKEY_AUTH_MODE` | `none` | `iam` for MemoryDB (no password) |
| `VALKEY_USER` | — | MemoryDB user name |
| `VALKEY_CLUSTER_NAME` | — | Cluster **name** for IAM signing (not the endpoint) |
| `STREAM_KEY` | `vehicle:vin-events` | |

See `ValkeyConfig.java` and `docker-compose.yml` for the full list.

## Deploy to AWS

Build/push images to ECR, run on ECS Fargate against a MemoryDB cluster with IAM auth. Step-by-step walkthrough:

**[PHASE2-CONSOLE-GUIDE.md](./PHASE2-CONSOLE-GUIDE.md)**

## Stack

Java 21 · Lettuce · Valkey 8 (local) / MemoryDB (AWS) · Docker · ECS Fargate
