# Deploy vehicle-stream-prototype to AWS: CloudFormation -> Docker push -> ECS rollout.
#
# Usage:
#   .\cloudformation\deploy.ps1
#   .\cloudformation\deploy.ps1 -Region eu-central-1 -StackName vehicle-stream
#   .\cloudformation\deploy.ps1 -SkipInfra
#   .\cloudformation\deploy.ps1 -SkipImages

param(
    [string]$StackName = "vehicle-stream",
    [string]$Region = $(if ($env:AWS_REGION) { $env:AWS_REGION } elseif ($env:AWS_DEFAULT_REGION) { $env:AWS_DEFAULT_REGION } else { "eu-central-1" }),
    [string]$ProjectName = "vehicle",
    [string]$ImageTag = "latest",
    [string]$VpcId = "",
    [string]$SubnetIds = "",
    [switch]$SkipInfra,
    [switch]$SkipImages
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir

function Get-StackOutput {
    param([string]$Key)
    aws cloudformation describe-stacks `
        --stack-name $StackName `
        --region $Region `
        --query "Stacks[0].Outputs[?OutputKey=='$Key'].OutputValue" `
        --output text
}

Write-Host "==> Account check"
$AccountId = aws sts get-caller-identity --query Account --output text --region $Region
Write-Host "    Account: $AccountId  Region: $Region  Stack: $StackName"

if (-not $SkipInfra) {
    if (-not $VpcId) {
        $VpcId = aws ec2 describe-vpcs --filters Name=isDefault,Values=true `
            --query "Vpcs[0].VpcId" --output text --region $Region
        if (-not $VpcId -or $VpcId -eq "None") {
            throw "No default VPC found. Pass -VpcId and -SubnetIds."
        }
        Write-Host "    Using default VPC: $VpcId"
    }

    if (-not $SubnetIds) {
        $subnetList = aws ec2 describe-subnets --filters Name=vpc-id,Values=$VpcId `
            --query "Subnets[*].SubnetId" --output text --region $Region
        $SubnetIds = ($subnetList -split "\s+") -join ","
        Write-Host "    Subnets: $SubnetIds"
    }

    Write-Host "==> Deploying CloudFormation stack (MemoryDB takes ~5-10 min on first create)"
    aws cloudformation deploy `
        --template-file "$ScriptDir\template.yaml" `
        --stack-name $StackName `
        --parameter-overrides `
            "ProjectName=$ProjectName" `
            "VpcId=$VpcId" `
            "SubnetIds=$SubnetIds" `
            "ImageTag=$ImageTag" `
        --capabilities CAPABILITY_NAMED_IAM `
        --region $Region `
        --no-fail-on-empty-changeset
    Write-Host "    Stack ready."
} else {
    Write-Host "==> Skipping CloudFormation (-SkipInfra)"
}

$ConsumerUri = Get-StackOutput "ConsumerRepositoryUri"
$ProducerUri = Get-StackOutput "ProducerRepositoryUri"
$EcsCluster = Get-StackOutput "EcsClusterName"
$ConsumerSvc = Get-StackOutput "ConsumerServiceName"
$ProducerSvc = Get-StackOutput "ProducerServiceName"
$LogGroup = Get-StackOutput "CloudWatchLogGroup"
$MemoryDbEndpoint = Get-StackOutput "MemoryDBClusterEndpoint"

if (-not $SkipImages) {
    Write-Host "==> Building and pushing Docker images"
    aws ecr get-login-password --region $Region |
        docker login --username AWS --password-stdin "$AccountId.dkr.ecr.$Region.amazonaws.com"

    docker build -t vehicle-consumer "$ProjectRoot\consumer"
    docker build -t vehicle-producer "$ProjectRoot\producer"
    docker tag "vehicle-consumer:latest" "${ConsumerUri}:${ImageTag}"
    docker tag "vehicle-producer:latest" "${ProducerUri}:${ImageTag}"
    docker push "${ConsumerUri}:${ImageTag}"
    docker push "${ProducerUri}:${ImageTag}"
    Write-Host "    Images pushed."

    Write-Host "==> Rolling ECS services"
    aws ecs update-service --cluster $EcsCluster --service $ConsumerSvc `
        --force-new-deployment --region $Region --no-cli-pager | Out-Null
    aws ecs update-service --cluster $EcsCluster --service $ProducerSvc `
        --force-new-deployment --region $Region --no-cli-pager | Out-Null
    Write-Host "    Rollout started."
} else {
    Write-Host "==> Skipping image build/push (-SkipImages)"
}

Write-Host ""
Write-Host "Done."
Write-Host "  MemoryDB endpoint : $MemoryDbEndpoint"
Write-Host "  ECS cluster       : $EcsCluster"
Write-Host "  CloudWatch logs   : $LogGroup (stream prefix: consumer)"
Write-Host ""
Write-Host "Teardown:"
Write-Host "  aws cloudformation delete-stack --stack-name $StackName --region $Region"
