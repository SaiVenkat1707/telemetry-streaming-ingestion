# Phase 2 — Deploy to AWS via the Management Console (eu-central-1)

A hands-on, first-timer walkthrough. You do everything in the **AWS Console**
logged into YOUR account. Region for everything: **Frankfurt (eu-central-1)** —
check the region selector (top-right of the console) reads "Frankfurt" on
*every* page before you click create.

## What we're building

```
                 default VPC (eu-central-1)
  ┌─────────────────────────────────────────────────────┐
  │  ECS Fargate                          MemoryDB        │
  │  ┌──────────────┐   6379 (TLS)   ┌──────────────────┐ │
  │  │ producer task │ ─────────────▶ │ Valkey cluster   │ │
  │  │ consumer task │ ◀───────────── │ (KV + stream)    │ │
  │  └──────┬───────┘                 └──────────────────┘ │
  │         │ logs                                          │
  └─────────┼─────────────────────────────────────────────┘
            ▼
        CloudWatch Logs  ◀── you read the printed snapshots here
```

Both services run as Fargate tasks **inside the VPC** so they can reach MemoryDB
privately (MemoryDB is never public). They get a **public IP** so they can pull
images from ECR and ship logs without us paying for a NAT gateway.

> 💰 **Cost warning.** A MemoryDB `db.t4g.small` node runs ~$0.06–0.07/hour
> (~$50/month if left on); two tiny Fargate tasks add a few cents/hour. This is
> cheap for a few hours of learning but NOT free. **Do Part 9 (teardown) when
> you're done** or it bills until you delete it.

---

## Part 0 — Prerequisites

- You're logged into the **correct** AWS account in your browser.
- Docker Desktop / engine running locally (to build & push images).
- Your terminal's AWS CLI is authenticated to the **same** correct account.
  Verify before pushing:
  ```bash
  aws sts get-caller-identity
  ```
  Confirm the `Account` shown is the one you intend to use. (The CLI here is
  currently pointed at a different account — fix that first.)

---

## Part 1 — Create ECR repositories and push the images

ECR = Elastic Container Registry, AWS's private Docker image store.

### 1a. Create two repositories (Console)
1. Console → search **ECR** → **Elastic Container Registry**.
2. **Repositories** → **Create repository**.
3. Visibility: **Private**. Name: `vehicle-consumer`. Leave defaults → **Create**.
4. Repeat for `vehicle-producer`.

You'll now see two repo URIs like:
`782412159464.dkr.ecr.eu-central-1.amazonaws.com/vehicle-consumer`

### 1b. Build & push (your terminal)

> ⚠️ **STOP — wrong account by default.** Pushing to ECR uses whatever account
> the CLI is authenticated as. The CLI on this machine is currently the WRONG
> account. You CANNOT push images from the Console — it must be the CLI, pointed
> at the correct account. Fix that first, with a named profile so you don't touch
> the existing creds:
>
> ```bash
> # Option A — IAM access keys for the CORRECT account
> aws configure --profile vehicle        # region: eu-central-1
> # Option B — IAM Identity Center / SSO
> aws configure sso --profile vehicle && aws sso login --profile vehicle
>
> # GATE: confirm this prints YOUR correct account number before continuing
> aws sts get-caller-identity --profile vehicle
>
> export AWS_PROFILE=vehicle             # make every command below use it
> ```

Replace `782412159464` with your real account number.

```bash
cd /home/vamsi/saivenkat/vehicle-stream-prototype
ACCOUNT_ID=782412159464                  # your CORRECT account, matches get-caller-identity above
REGION=eu-central-1
ECR=$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com

# 1) Log Docker into ECR (uses AWS_PROFILE=vehicle set above)
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ECR

# 2) Build both images (host is x86_64 → matches Fargate X86_64 default)
docker build -t vehicle-consumer ./consumer
docker build -t vehicle-producer ./producer

# 3) Tag for ECR
docker tag vehicle-consumer:latest $ECR/vehicle-consumer:latest
docker tag vehicle-producer:latest $ECR/vehicle-producer:latest

# 4) Push
docker push $ECR/vehicle-consumer:latest
docker push $ECR/vehicle-producer:latest
```

Refresh the ECR repos in the console — each should show an image tagged `latest`.

---

## Part 2 — Security groups

Two SGs in the **default VPC**. Console → **VPC** → **Security groups** →
**Create security group** (do this twice). Make sure VPC = your default VPC.

### 2a. `memorydb-sg`
- Name: `memorydb-sg`, description: `MemoryDB access`.
- Inbound rules: leave empty for now (we add a rule referencing the app SG after
  it exists).
- Outbound: leave default (all traffic). → **Create**.

### 2b. `vehicle-app-sg`
- Name: `vehicle-app-sg`, description: `Fargate vehicle services`.
- Inbound rules: **none** (the services only make outbound connections).
- Outbound: leave default (all traffic). → **Create**.

### 2c. Now wire them together
- Open **`memorydb-sg`** → **Inbound rules** → **Edit inbound rules** → **Add rule**:
  - Type: **Custom TCP**, Port range: **6379**
  - Source: **Custom** → start typing `vehicle-app-sg` and select its SG id.
  - **Save rules.**

This says "only the Fargate services may reach MemoryDB on 6379." Note both SG
ids (sg-xxxx) — you'll pick them later.

---

## Part 3 — MemoryDB (Valkey) cluster

Console → search **MemoryDB** → **MemoryDB for Valkey/Redis OSS**.

### 3a. Subnet group
1. Left nav → **Subnet groups** → **Create subnet group**.
2. Name: `vehicle-subnet-group`. VPC: your **default VPC**.
3. Add **at least 2 subnets** in different AZs (the default VPC's subnets are
   fine). → **Create**.

### 3b. ACL + user — IAM authentication (no password)
We authenticate with **IAM**, so the MemoryDB user has **no stored password** —
the app proves its identity using its Fargate task role (set up in Part 3.5).
1. **Users** → **Create user**.
   - User name: `vehicle-app`
   - Authentication mode: **IAM** (not Password).
   - Access string (permissions): `on ~* +@all` (full access — fine for a demo).
   - **Create.**
   > The MemoryDB user name does NOT have to match the IAM role name. This
   > `vehicle-app` name is what the app passes as the AUTH username and as the
   > `User` field when signing the token (`VALKEY_USER` env var).
2. **Access Control Lists** → **Create ACL**.
   - Name: `vehicle-acl`. Add the `vehicle-app` user. → **Create.**

### 3c. Create the cluster
1. **Clusters** → **Create cluster**.
2. Cluster info:
   - Name: `vehicle-memorydb`  ← this exact name is your `VALKEY_CLUSTER_NAME`
     (used to *sign* the IAM token).
   - Engine: **Valkey**, engine version **7.0 or above** (IAM auth requires ≥ 7.0).
3. Node type: **db.t4g.small** (smallest / cheapest).
4. Number of shards: **1**. Replicas per shard: **0** (cheapest; fine for demo).
   > ⚠️ Keep it at **1 shard**. The app uses a standalone Valkey client, which is
   > correct for a single-shard cluster. If you ever scale to multiple shards,
   > the client must switch to a cluster client (`RedisClusterClient`).
5. Subnet group: `vehicle-subnet-group`.
6. Security group: select **`memorydb-sg`** (remove the default if added).
7. ACL: select **`vehicle-acl`**.
8. Port: **6379**. (TLS is **always on** for MemoryDB — you can't disable it.)
9. Leave snapshot/maintenance defaults → **Create.**

Wait ~5–10 min until status = **Available**. Then open the cluster and copy the
**Cluster endpoint** hostname — looks like:
`clustercfg.vehicle-memorydb.xxxx.memorydb.eu-central-1.amazonaws.com`
That's your `VALKEY_HOST` (the DNS we connect to — distinct from the cluster
*name* used for signing).

> ⚠️ **Strip the `:6379`.** The console shows the endpoint as `host:6379`.
> `VALKEY_HOST` must be the **hostname only** — the port goes in `VALKEY_PORT`.
> Including the port causes `IllegalArgumentException: Host has a port` and the
> container exits with code 1 before connecting.

---

## Part 3.5 — IAM policy + task role (this is what IAM auth needs)

The Fargate task assumes a **task role**; that role's policy grants
`memorydb:connect`. There is no password anywhere — the role IS the credential.

> Two different roles, don't confuse them:
> - **Task role** = what your app code is allowed to do (here: connect to MemoryDB).
> - **Execution role** (`ecsTaskExecutionRole`, Part 6) = what the ECS agent does
>   on your behalf (pull the image from ECR, write logs).

### 3.5a. Create the policy
Console → **IAM** → **Policies** → **Create policy** → **JSON** tab → paste
(replace `782412159464`):
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["memorydb:connect"],
      "Resource": [
        "arn:aws:memorydb:eu-central-1:782412159464:cluster/vehicle-memorydb",
        "arn:aws:memorydb:eu-central-1:782412159464:user/vehicle-app"
      ]
    }
  ]
}
```
Name it `vehicle-memorydb-connect` → **Create policy**.

### 3.5b. Create the task role
Console → **IAM** → **Roles** → **Create role**.
- Trusted entity: **AWS service** → use case **Elastic Container Service** →
  **Elastic Container Service Task**.
- Attach the `vehicle-memorydb-connect` policy.
- Name: `vehicle-task-role` → **Create role.**

You'll select this as the **Task role** in both task definitions (Part 6).

---

## Part 4 — CloudWatch log group

Console → **CloudWatch** → **Log groups** → **Create log group**.
- Name: `/ecs/vehicle`. → **Create.**

(The ECS task wizard can also auto-create this, but making it now keeps names tidy.)

---

## Part 5 — ECS cluster

Console → **ECS (Elastic Container Service)** → **Clusters** → **Create cluster**.
- Name: `vehicle-cluster`.
- Infrastructure: **AWS Fargate (serverless)** (default). → **Create.**

---

## Part 6 — Task definitions (one per service)

A task definition is the "recipe" for a container: image, CPU/memory, env vars,
logging.

### 6.0 — Fast path: register from JSON (recommended)

Account/region/image are already filled in for `782412159464` / `eu-central-1`.
Before using these:

1. **Edit one value** in each JSON: set `VALKEY_HOST` to your real cluster
   endpoint (replace `REPLACE_WITH_CLUSTER_ENDPOINT` — from Part 3c).
   **Hostname only — do NOT include `:6379`** (the port is the separate
   `VALKEY_PORT` field).
2. Make sure both roles exist: `ecsTaskExecutionRole` (create it once if missing —
   AWS attaches the `AmazonECSTaskExecutionRolePolicy`) and `vehicle-task-role`
   (Part 3.5). The JSON references both by ARN.

To register, either way:
- **Console:** ECS → **Task definitions** → **Create new task definition with JSON**
  → paste one block → **Create**. Repeat for the other.
- **CLI:** save a block to a file and run
  `aws ecs register-task-definition --cli-input-json file://<file>.json --region eu-central-1`
  (needs `ecs:RegisterTaskDefinition` + `iam:PassRole`; the `ecr-push-user` may
  lack these — use the console paste option if so).

**Consumer task definition:**
```json
{
  "family": "vehicle-consumer",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "256",
  "memory": "512",
  "runtimePlatform": {
    "cpuArchitecture": "X86_64",
    "operatingSystemFamily": "LINUX"
  },
  "executionRoleArn": "arn:aws:iam::782412159464:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::782412159464:role/vehicle-task-role",
  "containerDefinitions": [
    {
      "name": "consumer",
      "image": "782412159464.dkr.ecr.eu-central-1.amazonaws.com/vehicle-consumer:latest",
      "essential": true,
      "environment": [
        { "name": "VALKEY_HOST", "value": "REPLACE_WITH_CLUSTER_ENDPOINT" },
        { "name": "VALKEY_PORT", "value": "6379" },
        { "name": "VALKEY_TLS", "value": "true" },
        { "name": "VALKEY_AUTH_MODE", "value": "iam" },
        { "name": "VALKEY_USER", "value": "vehicle-app" },
        { "name": "VALKEY_CLUSTER_NAME", "value": "vehicle-memorydb" },
        { "name": "AWS_REGION", "value": "eu-central-1" },
        { "name": "STREAM_KEY", "value": "vehicle:vin-events" },
        { "name": "BLOCK_MS", "value": "5000" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/vehicle",
          "awslogs-region": "eu-central-1",
          "awslogs-stream-prefix": "consumer"
        }
      }
    }
  ]
}
```

**Producer task definition:**
```json
{
  "family": "vehicle-producer",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "256",
  "memory": "512",
  "runtimePlatform": {
    "cpuArchitecture": "X86_64",
    "operatingSystemFamily": "LINUX"
  },
  "executionRoleArn": "arn:aws:iam::782412159464:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::782412159464:role/vehicle-task-role",
  "containerDefinitions": [
    {
      "name": "producer",
      "image": "782412159464.dkr.ecr.eu-central-1.amazonaws.com/vehicle-producer:latest",
      "essential": true,
      "environment": [
        { "name": "VALKEY_HOST", "value": "REPLACE_WITH_CLUSTER_ENDPOINT" },
        { "name": "VALKEY_PORT", "value": "6379" },
        { "name": "VALKEY_TLS", "value": "true" },
        { "name": "VALKEY_AUTH_MODE", "value": "iam" },
        { "name": "VALKEY_USER", "value": "vehicle-app" },
        { "name": "VALKEY_CLUSTER_NAME", "value": "vehicle-memorydb" },
        { "name": "AWS_REGION", "value": "eu-central-1" },
        { "name": "STREAM_KEY", "value": "vehicle:vin-events" },
        { "name": "PRODUCE_INTERVAL_MS", "value": "2000" },
        { "name": "SNAPSHOT_TTL_SECONDS", "value": "3600" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/vehicle",
          "awslogs-region": "eu-central-1",
          "awslogs-stream-prefix": "producer"
        }
      }
    }
  ]
}
```

If you use the JSON path, skip 6a/6b below (they're the click-by-click equivalent)
and go to **Part 7**. The notes in 6a/6b still explain what each field means.

### 6a. Consumer task definition (click-by-click equivalent of the JSON)
1. Family: `vehicle-consumer`.
2. Launch type: **AWS Fargate**. OS/Arch: **Linux/X86_64**.
3. Task size: CPU **0.25 vCPU**, Memory **0.5 GB** (smallest).
4. **Task role: `vehicle-task-role`** (from Part 3.5 — this is what grants
   `memorydb:connect`, so IAM auth works).
   Task execution role: **Create new role** (or pick `ecsTaskExecutionRole` if it
   already exists) — this lets ECS pull from ECR and write logs.
5. Container:
   - Name: `consumer`
   - Image URI: `782412159464.dkr.ecr.eu-central-1.amazonaws.com/vehicle-consumer:latest`
   - Port mappings: none needed.
   - **Environment variables** (add each) — note: **no password anywhere**:
     | Key | Value |
     |-----|-------|
     | `VALKEY_HOST` | cluster **endpoint** from Part 3c, **hostname only — no `:6379`** |
     | `VALKEY_PORT` | `6379` |
     | `VALKEY_TLS` | `true` |
     | `VALKEY_AUTH_MODE` | `iam` |
     | `VALKEY_USER` | `vehicle-app` |
     | `VALKEY_CLUSTER_NAME` | `vehicle-memorydb` (the cluster **name**, for signing) |
     | `STREAM_KEY` | `vehicle:vin-events` |
     | `BLOCK_MS` | `5000` |
   - (`AWS_REGION` is set automatically by Fargate, so the token signer picks up
     `eu-central-1` on its own. You can add it explicitly if you like.)
   - Logging: **Use log collection** → driver **awslogs** → log group `/ecs/vehicle`,
     stream prefix `consumer`, region `eu-central-1`.
6. **Create.**

> 🔒 Why this is nicer than a password: there's no secret in the task definition,
> nothing to rotate, and connections are tied to `vehicle-task-role` in CloudTrail.
> The app generates a fresh 15-minute SigV4 token per connection from the role.

### 6b. Producer task definition
Repeat 6a with:
- Family: `vehicle-producer`
- **Task role: `vehicle-task-role`** (same role).
- Container name: `producer`
- Image URI: `.../vehicle-producer:latest`
- Same env vars as 6a EXCEPT replace `BLOCK_MS` with:
  | Key | Value |
  |-----|-------|
  | `PRODUCE_INTERVAL_MS` | `2000` |
  | `SNAPSHOT_TTL_SECONDS` | `3600` |
- Log stream prefix: `producer`.

---

## Part 7 — Run the services

For each task definition, create a Service so ECS keeps it running.

ECS → `vehicle-cluster` → **Services** → **Create**.

### 7a. Consumer service
1. Launch type: **Fargate**. Family: `vehicle-consumer` (latest revision).
2. Service name: `consumer-svc`. Desired tasks: **1**.
3. Networking:
   - VPC: **default VPC**.
   - Subnets: pick the same (public) subnets as the MemoryDB subnet group.
   - Security group: **`vehicle-app-sg`** (the one allowed into MemoryDB).
   - **Public IP: Turned ON** (required so it can reach ECR/CloudWatch without a
     NAT gateway).
4. **Create.**

### 7b. Producer service
Same steps with family `vehicle-producer`, service name `producer-svc`,
desired tasks **1**, same subnets, **`vehicle-app-sg`**, **Public IP ON**.

---

## Part 8 — Verify

1. ECS → `vehicle-cluster` → both services should reach **1/1 running tasks**
   (give it 1–2 min; first pull is slowest).
2. CloudWatch → **Log groups** → `/ecs/vehicle` → open the **consumer** stream.
3. You should see lines like:
   ```
   Snapshot for 1HGBH41JXMN109186 (stream id ...): {"vin":"1HGBH41JXMN109186",...}
   ```
   That's the full pipeline working on AWS. 🎉

### If a task won't start / keeps restarting
- ECS → service → **Tasks** tab → click the stopped task → **Stopped reason**.
- Common causes:
  - **CannotPullContainerError** → public IP is OFF, or image URI/tag wrong.
  - **Connection timeout to MemoryDB** → `memorydb-sg` inbound 6379 isn't
    sourced from `vehicle-app-sg`, or the task is in a different VPC/subnet.
  - **SSL/handshake errors** → `VALKEY_TLS` is not `true` (MemoryDB requires TLS).
  - **`WRONGPASS` / `NOPERM` / auth failures** (IAM-specific):
    - `VALKEY_AUTH_MODE` is not `iam`, or `VALKEY_USER` ≠ the MemoryDB user name.
    - `VALKEY_CLUSTER_NAME` is wrong — it must be the cluster **name**
      (`vehicle-memorydb`), NOT the endpoint DNS. A bad signing host = invalid token.
    - The MemoryDB user's auth mode isn't **IAM**, or it's not in `vehicle-acl`.
    - `vehicle-task-role` is missing/not attached to the task def, or its policy
      ARNs don't match the real cluster/user ARNs → `AccessDenied` on connect.
  - **`Unable to load credentials` in logs** → task role not set; the SDK found no
    credentials. Confirm Task role = `vehicle-task-role` on the task definition.

---

## Part 9 — Teardown (DO THIS WHEN DONE — stops billing)

In this order:
1. **ECS** → delete services `consumer-svc` and `producer-svc` (set desired to 0
   first if needed), then delete `vehicle-cluster`.
2. **MemoryDB** → delete cluster `vehicle-memorydb` (the big cost). Then delete
   the ACL, user, and subnet group.
3. **CloudWatch** → delete log group `/ecs/vehicle` (optional, tiny cost).
4. **ECR** → delete repos `vehicle-consumer`, `vehicle-producer` (optional).
5. **IAM** → delete role `vehicle-task-role` and policy `vehicle-memorydb-connect`
   (optional — they're free, but tidy).
6. **VPC** → delete security groups `vehicle-app-sg`, `memorydb-sg` (optional).
7. (Default VPC: leave it — it's free and shared.)

Confirm in the **Billing → Cost Explorer** the next day that nothing lingers.
