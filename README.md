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

- Docker + AWS CLI authenticated to your account (`aws sts get-caller-identity`)
- IAM permissions for CloudFormation, ECR, ECS, MemoryDB, VPC, IAM roles

> MemoryDB `db.t4g.small` costs ~$50/month if left running. Delete the stack when done.

## Deploy (CloudFormation)

One command provisions ECR, MemoryDB, IAM, ECS, and CloudWatch, then builds/pushes images and rolls the services:

```powershell
.\deploy.ps1 -Region eu-central-1
```

Uses your account's **default VPC and subnets** automatically. Override with `-VpcId` and `-SubnetIds`.

First create takes **~5–10 minutes** (MemoryDB). Re-runs only push new images and restart tasks.

```powershell
# Infra only (no docker build)
.\deploy.ps1 -SkipImages

# Re-push images after code changes (stack already exists)
.\deploy.ps1 -SkipInfra
```

**Teardown** (stops billing):

```bash
aws cloudformation delete-stack --stack-name vehicle-stream --region eu-central-1
```

Template: [`template.yaml`](./template.yaml)

## Verify

After ECS tasks reach **RUNNING**, tail consumer logs:

```bash
aws logs tail /ecs/vehicle --follow --log-stream-names-prefix consumer --region eu-central-1
```

Expected output:

```
Snapshot for 1HGBH41JXMN109186 (stream id ...): {"vin":"1HGBH41JXMN109186","speedKph":62.4,...}
```

## Manual deploy (Console)

Prefer clicking through the AWS Console? Same architecture, step by step:

**[PHASE2-CONSOLE-GUIDE.md](./PHASE2-CONSOLE-GUIDE.md)**

## Stack

Java 21 · Lettuce · AWS MemoryDB (Valkey) · ECR · ECS Fargate · CloudWatch · IAM auth

