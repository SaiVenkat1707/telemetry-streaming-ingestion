# Vehicle Stream Prototype

End-to-end vehicle telemetry pipeline on AWS: a producer stores JSON snapshots in **MemoryDB** keyed by VIN and pushes stream events carrying **only the VIN**; a consumer reads those events, looks up the snapshot, and logs it to **CloudWatch**.

```
                 VPC
  ┌─────────────────────────────────────────────────────┐
  │  ECS Fargate                          MemoryDB        │
  │  ┌──────────────┐   6379 (TLS)   ┌──────────────────┐ │
  │  │ producer task │ ─────────────▶ │ Valkey cluster   │ │
  │  │ consumer task │ ◀───────────── │ (KV + stream)    │ │
  │  └──────┬───────┘                 └──────────────────┘ │
  │         │ logs                                          │
  └─────────┼─────────────────────────────────────────────┘
            ▼
        CloudWatch Logs
```

- **`producer/`** — simulated upstream; emits a randomized snapshot every 2s
- **`consumer/`** — stream consumer with consumer-group semantics (`XREADGROUP`, `XACK`, `XAUTOCLAIM`)

Auth is **IAM-based** — no passwords. The task role grants `memorydb:connect`; the app signs a short-lived SigV4 token per connection.

## Prerequisites

- Docker (to build images)
- AWS CLI authenticated to your account (`aws sts get-caller-identity`)
- Permissions for ECR, ECS Fargate, MemoryDB, IAM, CloudWatch

> MemoryDB `db.t4g.small` costs ~$50/month if left running. Tear down when done — see the guide's teardown section.

## Deploy overview

1. **ECR** — create `vehicle-consumer` and `vehicle-producer` repos; build and push images
2. **MemoryDB** — single-shard Valkey ≥ 7.0 cluster, IAM user, ACL, security group on port 6379
3. **IAM** — task role with `memorydb:connect` on the cluster and user
4. **ECS** — Fargate cluster, task definitions for both services, services with public IP in the same VPC/subnets
5. **CloudWatch** — log group `/ecs/vehicle`; consumer output appears here

Full click-by-click walkthrough (Console, task-definition JSON, troubleshooting, teardown):

**[PHASE2-CONSOLE-GUIDE.md](./PHASE2-CONSOLE-GUIDE.md)**

## Build and push to ECR

```bash
ACCOUNT_ID=<your-account-id>
REGION=<your-region>          # e.g. eu-central-1
ECR=$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com

aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ECR

docker build -t vehicle-consumer ./consumer
docker build -t vehicle-producer ./producer
docker tag vehicle-consumer:latest $ECR/vehicle-consumer:latest
docker tag vehicle-producer:latest $ECR/vehicle-producer:latest
docker push $ECR/vehicle-consumer:latest
docker push $ECR/vehicle-producer:latest
```

## Required environment variables (AWS)

Set these on both ECS task definitions:

| Variable | Value |
|----------|-------|
| `VALKEY_HOST` | MemoryDB cluster endpoint — **hostname only, no `:6379`** |
| `VALKEY_PORT` | `6379` |
| `VALKEY_TLS` | `true` |
| `VALKEY_AUTH_MODE` | `iam` |
| `VALKEY_USER` | MemoryDB user name (e.g. `vehicle-app`) |
| `VALKEY_CLUSTER_NAME` | Cluster **name** for IAM signing — not the endpoint DNS |
| `AWS_REGION` | Your region (Fargate may set this automatically) |
| `STREAM_KEY` | `vehicle:vin-events` |

## Verify

ECS → both services at **1/1 running** → CloudWatch → `/ecs/vehicle` → **consumer** stream:

```
Snapshot for 1HGBH41JXMN109186 (stream id ...): {"vin":"1HGBH41JXMN109186","speedKph":62.4,...}
```

## Stack

Java 21 · Lettuce · AWS MemoryDB (Valkey) · ECR · ECS Fargate · CloudWatch · IAM auth

## Local development

```bash
docker compose up --build
```

Runs against a local Valkey container — no TLS, no IAM. Useful for testing before pushing to AWS.
