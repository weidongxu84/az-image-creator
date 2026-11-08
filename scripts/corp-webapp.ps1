[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet("start", "stop", "status")]
    [string]$Action,

    [switch]$Force,

    [ValidateRange(30, 1800)]
    [int]$HealthTimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$subscription = "weidxu dev subscription"
$resourceGroup = "appservice"
$appName = "weidxu-image-creator"
$healthUrl = "https://$appName.azurewebsites.net/actuator/health"
$activeJobsUrl = "https://$appName.azurewebsites.net/api/jobs/active"

function Invoke-Az {
    param([Parameter(Mandatory)][string[]]$Arguments)

    & az @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Azure CLI command failed: az $($Arguments -join ' ')"
    }
}

function Get-AppState {
    $state = & az webapp show `
        --name $appName `
        --resource-group $resourceGroup `
        --query state `
        --output tsv
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read the state of $appName."
    }
    return $state.Trim()
}

function Assert-NoActiveJobs {
    $settingsJson = & az webapp config appsettings list `
        --name $appName `
        --resource-group $resourceGroup `
        --output json
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read authentication settings for $appName."
    }

    $settings = $settingsJson | ConvertFrom-Json
    $username = ($settings | Where-Object name -eq "PERSONAL_USERNAME").value
    $token = ($settings | Where-Object name -eq "PERSONAL_TOKEN").value
    if ([string]::IsNullOrWhiteSpace($username) -or
        [string]::IsNullOrWhiteSpace($token)) {
        throw "Required app authentication settings are unavailable."
    }

    $credentials = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes("${username}:${token}"))
    try {
        $response = Invoke-RestMethod `
            -Uri $activeJobsUrl `
            -Headers @{ Authorization = "Basic $credentials" } `
            -Method Get `
            -TimeoutSec 30
    } catch {
        if ($null -ne $_.Exception.Response -and
            [int]$_.Exception.Response.StatusCode -eq 403) {
            Write-Warning "Active-job check returned HTTP 403. Continuing with control-plane stop and disabling public network access."
            return
        }
        throw
    }

    if ($null -eq $response.activeJobs) {
        throw "The active-job response did not contain activeJobs."
    }
    if ([int]$response.activeJobs -ne 0) {
        throw "Refusing to stop $appName because it has $($response.activeJobs) active job(s)."
    }
}

function Set-SelectedNetworkAccess {
    $requiredRules = @(
        [pscustomobject]@{
            Name       = "Allow CorpNetPublic"
            ServiceTag = "CorpNetPublic"
            Priority   = 101
        },
        [pscustomobject]@{
            Name       = "Allow CorpNetSaw"
            ServiceTag = "CorpNetSAW"
            Priority   = 102
        }
    )

    $restrictionsJson = & az webapp config access-restriction show `
        --name $appName `
        --resource-group $resourceGroup `
        --output json
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read access restrictions for $appName."
    }
    $restrictions = $restrictionsJson | ConvertFrom-Json

    foreach ($requiredRule in $requiredRules) {
        $existingRule = $restrictions.ipSecurityRestrictions |
            Where-Object name -eq $requiredRule.Name |
            Select-Object -First 1
        $isCorrect = $null -ne $existingRule -and
            $existingRule.action -eq "Allow" -and
            $existingRule.tag -eq "ServiceTag" -and
            $existingRule.ipAddress -eq $requiredRule.ServiceTag -and
            [int]$existingRule.priority -eq $requiredRule.Priority

        if ($isCorrect) {
            continue
        }
        if ($null -ne $existingRule) {
            Invoke-Az @(
                "webapp", "config", "access-restriction", "remove",
                "--name", $appName,
                "--resource-group", $resourceGroup,
                "--rule-name", $requiredRule.Name,
                "--output", "none")
        }
        Invoke-Az @(
            "webapp", "config", "access-restriction", "add",
            "--name", $appName,
            "--resource-group", $resourceGroup,
            "--rule-name", $requiredRule.Name,
            "--action", "Allow",
            "--service-tag", $requiredRule.ServiceTag,
            "--priority", $requiredRule.Priority,
            "--skip-service-tag-validation", "true",
            "--output", "none")
    }

    Invoke-Az @(
        "webapp", "config", "access-restriction", "set",
        "--name", $appName,
        "--resource-group", $resourceGroup,
        "--default-action", "Deny",
        "--scm-default-action", "Deny",
        "--use-same-restrictions-for-scm-site", "true",
        "--output", "none")
    Invoke-Az @(
        "webapp", "update",
        "--name", $appName,
        "--resource-group", $resourceGroup,
        "--set", "publicNetworkAccess=Enabled",
        "--output", "none")
}

function Disable-PublicNetworkAccess {
    Invoke-Az @(
        "webapp", "update",
        "--name", $appName,
        "--resource-group", $resourceGroup,
        "--set", "publicNetworkAccess=Disabled",
        "--output", "none")
}

function Wait-AppHealthy {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    do {
        try {
            $health = Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 30
            if ($health.status -eq "UP") {
                Write-Host "$appName is running and healthy."
                return
            }
        } catch {
            Write-Verbose "Health check pending: $($_.Exception.Message)"
        }

        Start-Sleep -Seconds 10
    } while ((Get-Date) -lt $deadline)

    throw "$appName did not become healthy within $HealthTimeoutSeconds seconds."
}

Invoke-Az @("account", "set", "--subscription", $subscription)
$state = Get-AppState

switch ($Action.ToLowerInvariant()) {
    "start" {
        Set-SelectedNetworkAccess
        if ($state -ne "Running") {
            Invoke-Az @(
                "webapp", "start",
                "--name", $appName,
                "--resource-group", $resourceGroup,
                "--output", "none")
        }
        Wait-AppHealthy
    }
    "stop" {
        if ($state -ne "Stopped") {
            if (-not $Force) {
                Assert-NoActiveJobs
            }
            Invoke-Az @(
                "webapp", "stop",
                "--name", $appName,
                "--resource-group", $resourceGroup,
                "--output", "none")
            $state = Get-AppState
            if ($state -ne "Stopped") {
                throw "$appName did not stop successfully. Current state: $state"
            }
        }
        Disable-PublicNetworkAccess
        Write-Host "$appName is stopped and public network access is disabled."
    }
    "status" {
        $planId = & az webapp show `
            --name $appName `
            --resource-group $resourceGroup `
            --query serverFarmId `
            --output tsv
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to read the App Service Plan for $appName."
        }
        $sku = & az appservice plan show `
            --ids $planId.Trim() `
            --query sku.name `
            --output tsv
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to read the App Service Plan SKU."
        }
        $publicNetworkAccess = & az webapp show `
            --name $appName `
            --resource-group $resourceGroup `
            --query publicNetworkAccess `
            --output tsv
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to read public network access for $appName."
        }
        [pscustomobject]@{
            Subscription = $subscription
            App          = $appName
            State        = $state
            Sku          = $sku.Trim()
            Network      = $publicNetworkAccess.Trim()
        }
    }
}
