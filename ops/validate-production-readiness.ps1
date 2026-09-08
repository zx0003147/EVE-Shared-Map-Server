param(
    [Parameter()]
    [string]$DockerBin = 'docker',
    [Parameter()]
    [string]$MapRepository = '',
    [Parameter()]
    [string]$ServerImage = 'eve-shared-map-server:phase8a',
    [Parameter()]
    [string]$OpsImage = 'eve-shared-map-ops:phase8a',
    [Parameter()]
    [string]$ExpectedServerVersion = '0.2.0'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'docker-compose.prod.yml'
$validationComposeFile = Join-Path $repoRoot 'ops/docker-compose.validation.yml'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$projectName = "esmphase8a$runId"
$restoreContainer = "$projectName-restore-server"
$testRoot = Join-Path $repoRoot ".phase8a-validation-$runId"
$secretRoot = Join-Path $testRoot 'secrets'
$stagingRoot = Join-Path $testRoot 'staging'
$offsiteRoot = Join-Path $testRoot 'offsite'
$webRoot = Join-Path $testRoot 'web'
$envFile = Join-Path $testRoot '.env.production'
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$httpPort = 0
$httpsPort = 0
$validationServerPort = 0
$client = $null
$token = $null

function Invoke-Docker {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = & $DockerBin @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed with exit code ${LASTEXITCODE}."
    }
    return $output
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    return Invoke-Docker compose --project-name $projectName --env-file $envFile --file $composeFile @Arguments
}

function Invoke-ValidationCompose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    return Invoke-Docker compose --project-name $projectName --env-file $envFile --file $composeFile --file $validationComposeFile @Arguments
}

function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Write-Secret {
    param([string]$Path, [string]$Value)
    [IO.File]::WriteAllText($Path, "$Value`n", $utf8NoBom)
}

function Get-RandomSecret {
    return [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
}

function Wait-Healthy {
    param([string]$Service, [int]$Attempts = 60)
    for ($index = 0; $index -lt $Attempts; $index++) {
        $containerId = (Invoke-Compose ps --quiet $Service | Select-Object -First 1).Trim()
        if ($containerId) {
            $state = (Invoke-Docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId | Select-Object -First 1).Trim()
            if ($state -eq 'healthy') { return $containerId }
            if ($state -in @('unhealthy', 'exited', 'dead')) {
                $logs = Invoke-Compose logs --tail 100 $Service
                throw "$Service entered $state.`n$($logs -join "`n")"
            }
        }
        Start-Sleep -Seconds 2
    }
    throw "$Service did not become healthy."
}

function Send-ApiRequest {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null,
        [string]$BearerToken = '',
        [string]$IdempotencyKey = ''
    )
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Url)
    try {
        if ($BearerToken) {
            $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $BearerToken)
        }
        if ($IdempotencyKey) {
            $null = $request.Headers.TryAddWithoutValidation('Idempotency-Key', $IdempotencyKey)
        }
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 12 -Compress
            $request.Content = [Net.Http.StringContent]::new($json, $utf8NoBom, 'application/json')
        }
        $response = $client.Send($request)
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "$Method $Url returned $([int]$response.StatusCode): $content"
        }
        return [pscustomobject]@{
            Response = $response
            Json = if ($content) { $content | ConvertFrom-Json } else { $null }
            Text = $content
        }
    } finally {
        $request.Dispose()
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-ContentType {
    param([string]$Url, [string]$ExpectedMediaType)
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, $Url)
    $response = $client.Send($request)
    try {
        Assert-True $response.IsSuccessStatusCode "Static asset was not readable: $Url"
        Assert-True ([string]$response.Content.Headers.ContentType.MediaType -eq $ExpectedMediaType) "Unexpected MIME type for ${Url}: $($response.Content.Headers.ContentType)"
    } finally {
        $response.Dispose()
        $request.Dispose()
    }
}

try {
    Assert-True (Test-Path -LiteralPath $DockerBin -PathType Leaf) "Docker CLI was not found at $DockerBin"
    New-Item -ItemType Directory -Path $secretRoot, $stagingRoot, $offsiteRoot, (Join-Path $webRoot 'current/data') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $webRoot 'current/index.html'), '<!doctype html><title>EVE Map validation</title>', $utf8NoBom)
    [IO.File]::WriteAllText((Join-Path $webRoot 'current/service-worker.js'), 'self.addEventListener("fetch", () => {});', $utf8NoBom)
    [IO.File]::WriteAllText((Join-Path $webRoot 'current/web-client.js'), 'console.log("validation");', $utf8NoBom)
    [IO.File]::WriteAllText((Join-Path $webRoot 'current/web-pack-loader.mjs'), 'export const validation = true;', $utf8NoBom)
    [IO.File]::WriteAllText((Join-Path $webRoot 'current/web-client.css'), 'body { color: black; }', $utf8NoBom)
    [IO.File]::WriteAllText((Join-Path $webRoot 'current/manifest.webmanifest'), '{"name":"EVE Map validation"}', $utf8NoBom)
    [IO.File]::WriteAllBytes((Join-Path $webRoot 'current/app-icon.png'), [byte[]](137, 80, 78, 71, 13, 10, 26, 10))
    $validationPack = [IO.Compression.GZipStream]::new(
        [IO.File]::Create((Join-Path $webRoot 'current/data/web-pack-validation.json.gz')),
        [IO.Compression.CompressionLevel]::Optimal
    )
    try {
        $packBytes = $utf8NoBom.GetBytes('{"schemaVersion":1,"packVersion":"validation"}')
        $validationPack.Write($packBytes, 0, $packBytes.Length)
    } finally {
        $validationPack.Dispose()
    }
    $packPath = Join-Path $webRoot 'current/data/web-pack-validation.json.gz'
    $packHash = (Get-FileHash -LiteralPath $packPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $packSize = (Get-Item -LiteralPath $packPath).Length
    [IO.File]::WriteAllText(
        (Join-Path $webRoot 'current/data/manifest.json'),
        "{`"schemaVersion`":1,`"packVersion`":`"validation`",`"sdeBuild`":3466501,`"fileName`":`"web-pack-validation.json.gz`",`"sizeBytes`":$packSize,`"sha256`":`"$packHash`"}",
        $utf8NoBom
    )
    Write-Secret (Join-Path $secretRoot 'postgres-database-user.txt') 'phase8a_user'
    Write-Secret (Join-Path $secretRoot 'server-database-user.txt') 'phase8a_user'
    $databasePassword = Get-RandomSecret
    Write-Secret (Join-Path $secretRoot 'postgres-database-password.txt') $databasePassword
    Write-Secret (Join-Path $secretRoot 'server-database-password.txt') $databasePassword
    $databasePassword = $null
    Write-Secret (Join-Path $secretRoot 'token-pepper.txt') (Get-RandomSecret)
    Write-Secret (Join-Path $secretRoot 'backup-passphrase.txt') (Get-RandomSecret)

    $httpPort = Get-FreePort
    $httpsPort = Get-FreePort
    $validationServerPort = Get-FreePort
    $toDockerPath = { param([string]$Path) ([IO.Path]::GetFullPath($Path) -replace '\\', '/') }
    $envContent = @"
SHARED_MAP_DOMAIN=localhost
SHARED_MAP_WEB_DOMAIN=web.localhost
SHARED_MAP_ACME_EMAIL=phase8a@example.invalid
SHARED_MAP_HTTP_PORT=$httpPort
SHARED_MAP_HTTPS_PORT=$httpsPort
SHARED_MAP_VALIDATION_SERVER_PORT=$validationServerPort
SHARED_MAP_VALIDATION_WEB_ROOT="$(& $toDockerPath $webRoot)"
SHARED_MAP_ALLOWED_ORIGINS=https://web.localhost:$httpsPort
SHARED_MAP_SERVER_IMAGE=$ServerImage
SHARED_MAP_OPS_IMAGE=$OpsImage
SHARED_MAP_POSTGRES_IMAGE=postgres:18.6-alpine
SHARED_MAP_CADDY_IMAGE=caddy:2.10.2-alpine
SHARED_MAP_DATABASE_NAME=phase8a
SHARED_MAP_POSTGRES_DATABASE_USER_FILE="$(& $toDockerPath (Join-Path $secretRoot 'postgres-database-user.txt'))"
SHARED_MAP_POSTGRES_DATABASE_PASSWORD_FILE="$(& $toDockerPath (Join-Path $secretRoot 'postgres-database-password.txt'))"
SHARED_MAP_SERVER_DATABASE_USER_FILE="$(& $toDockerPath (Join-Path $secretRoot 'server-database-user.txt'))"
SHARED_MAP_SERVER_DATABASE_PASSWORD_FILE="$(& $toDockerPath (Join-Path $secretRoot 'server-database-password.txt'))"
SHARED_MAP_TOKEN_PEPPER_FILE="$(& $toDockerPath (Join-Path $secretRoot 'token-pepper.txt'))"
SHARED_MAP_BACKUP_PASSPHRASE_FILE="$(& $toDockerPath (Join-Path $secretRoot 'backup-passphrase.txt'))"
SHARED_MAP_BACKUP_STAGING_HOST_DIR="$(& $toDockerPath $stagingRoot)"
SHARED_MAP_BACKUP_OFFSITE_HOST_DIR="$(& $toDockerPath $offsiteRoot)"
SHARED_MAP_BACKUP_DAILY_RETENTION=30
SHARED_MAP_BACKUP_MONTHLY_RETENTION=12
SHARED_MAP_EXPECTED_FLYWAY_VERSION=4
SHARED_MAP_LOG_LEVEL=INFO
"@
    [IO.File]::WriteAllText($envFile, $envContent, $utf8NoBom)

    $null = Invoke-Compose --profile ops config --quiet
    $null = Invoke-ValidationCompose up --detach postgres shared-map-server caddy
    $postgresId = Wait-Healthy postgres
    $serverId = Wait-Healthy shared-map-server
    $caddyId = Wait-Healthy caddy

    $postgresInspect = (Invoke-Docker inspect $postgresId | ConvertFrom-Json)[0]
    $serverInspect = (Invoke-Docker inspect $serverId | ConvertFrom-Json)[0]
    $postgresPortBinding = $postgresInspect.HostConfig.PortBindings.PSObject.Properties | Where-Object Name -eq '5432/tcp'
    $serverPortBinding = $serverInspect.HostConfig.PortBindings.PSObject.Properties | Where-Object Name -eq '8080/tcp'
    Assert-True (-not $postgresPortBinding -or $null -eq $postgresPortBinding.Value) 'PostgreSQL unexpectedly has a host port binding.'
    $serverBindings = @($serverPortBinding.Value)
    Assert-True ($serverBindings.Count -eq 1) 'Validation Server must have exactly one loopback-only host binding.'
    Assert-True ($serverBindings[0].HostIp -eq '127.0.0.1') 'Validation Server host binding was not loopback-only.'
    Assert-True ([int]$serverBindings[0].HostPort -eq $validationServerPort) 'Validation Server used an unexpected host port.'
    Assert-True ($serverInspect.Config.User -eq '10001:10001') 'Server is not running as UID/GID 10001.'
    Assert-True ([bool]$serverInspect.HostConfig.ReadonlyRootfs) 'Server root filesystem is not read-only.'
    Assert-True ($serverInspect.HostConfig.CapDrop -contains 'ALL') 'Server capabilities were not dropped.'
    Assert-True ($serverInspect.HostConfig.SecurityOpt -contains 'no-new-privileges:true') 'Server no-new-privileges is missing.'
    foreach ($target in '/run/secrets/db_username', '/run/secrets/db_password', '/run/secrets/token_pepper') {
        Assert-True ([bool]($serverInspect.Mounts | Where-Object Destination -eq $target)) "Missing Server secret mount: $target"
    }
    Assert-True ($serverInspect.HostConfig.LogConfig.Config.'max-size' -eq '10m') 'Server log max-size is not bounded.'
    Assert-True ($serverInspect.HostConfig.LogConfig.Config.'max-file' -eq '5') 'Server log max-file is not bounded.'

    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.ServerCertificateCustomValidationCallback = [Net.Http.HttpClientHandler]::DangerousAcceptAnyServerCertificateValidator
    $handler.AllowAutoRedirect = $false
    $handler.UseProxy = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    $httpsBase = "https://localhost:$httpsPort"
    $webBase = "https://web.localhost:$httpsPort"
    $httpBase = "http://localhost:$httpPort"

    $redirectRequest = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, "$httpBase/health")
    $redirectResponse = $client.Send($redirectRequest)
    try {
        Assert-True ([int]$redirectResponse.StatusCode -in @(301, 302, 307, 308)) 'Caddy did not redirect HTTP to HTTPS.'
    } finally {
        $redirectResponse.Dispose()
        $redirectRequest.Dispose()
    }

    $health = $null
    for ($index = 0; $index -lt 30; $index++) {
        try {
            $health = Send-ApiRequest GET "$httpsBase/health"
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    Assert-True ($null -ne $health) 'Caddy HTTPS listener did not become ready.'
    Assert-True ($health.Json.status -eq 'ok') 'HTTPS health was not ok.'
    Assert-True ($health.Response.Headers.GetValues('Strict-Transport-Security') -contains 'max-age=31536000') 'HSTS header is missing.'
    Assert-True ($health.Response.Headers.GetValues('X-Content-Type-Options') -contains 'nosniff') 'nosniff header is missing.'
    $meta = Send-ApiRequest GET "$httpsBase/api/v1/meta"
    Assert-True ($health.Json.serverVersion -eq $ExpectedServerVersion) 'Health endpoint reported an unexpected Server version.'
    Assert-True ($meta.Json.serverVersion -eq $ExpectedServerVersion) 'Meta endpoint reported an unexpected Server version.'
    Assert-True ($meta.Json.protocolVersion -eq 1) 'Protocol metadata is not V1.'
    Assert-True ($meta.Json.features -contains 'shared-markers') 'Shared Marker feature metadata is missing.'
    Assert-True ($meta.Json.features -contains 'route-handoffs') 'Route Handoff feature metadata is missing.'
    Assert-True ([bool]$meta.Json.universeBuild) 'Universe build metadata is missing.'

    $corsRequest = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, "$httpsBase/api/v1/meta")
    $null = $corsRequest.Headers.TryAddWithoutValidation('Origin', $webBase)
    $corsResponse = $client.Send($corsRequest)
    try {
        Assert-True $corsResponse.IsSuccessStatusCode 'CORS validation request failed.'
        $allowedOrigins = @($corsResponse.Headers.GetValues('Access-Control-Allow-Origin'))
        Assert-True ($allowedOrigins.Count -eq 1 -and $allowedOrigins[0] -eq $webBase) 'Shared Marker did not return the exact Web origin for CORS.'
    } finally {
        $corsResponse.Dispose()
        $corsRequest.Dispose()
    }

    $webIndexRequest = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, "$webBase/")
    $webIndexResponse = $client.Send($webIndexRequest)
    try {
        $webIndexText = $webIndexResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        Assert-True $webIndexResponse.IsSuccessStatusCode 'Caddy did not serve the Web Map site.'
        Assert-True ($webIndexText -match 'EVE Map validation') 'Caddy returned unexpected Web Map content.'
        Assert-True ([string]$webIndexResponse.Content.Headers.ContentType.MediaType -eq 'text/html') 'Web Map HTML MIME type was incorrect.'
        Assert-True $webIndexResponse.Headers.CacheControl.NoCache 'Web Map HTML did not require revalidation.'
    } finally {
        $webIndexResponse.Dispose()
        $webIndexRequest.Dispose()
    }
    Assert-ContentType "$webBase/web-client.js" 'text/javascript'
    Assert-ContentType "$webBase/web-pack-loader.mjs" 'text/javascript'
    Assert-ContentType "$webBase/web-client.css" 'text/css'
    Assert-ContentType "$webBase/data/manifest.json" 'application/json'
    Assert-ContentType "$webBase/manifest.webmanifest" 'application/manifest+json'
    Assert-ContentType "$webBase/app-icon.png" 'image/png'
    $serviceWorkerRequest = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, "$webBase/service-worker.js")
    $serviceWorkerResponse = $client.Send($serviceWorkerRequest)
    try {
        Assert-True $serviceWorkerResponse.IsSuccessStatusCode 'Service worker was not publicly readable.'
        Assert-True $serviceWorkerResponse.Headers.CacheControl.NoCache 'Service worker did not require revalidation.'
    } finally {
        $serviceWorkerResponse.Dispose()
        $serviceWorkerRequest.Dispose()
    }
    $webManifestRequest = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, "$webBase/data/manifest.json")
    $webManifestResponse = $client.Send($webManifestRequest)
    try {
        Assert-True $webManifestResponse.IsSuccessStatusCode 'Web Pack manifest was not publicly readable.'
        Assert-True ($webManifestResponse.Headers.CacheControl.NoCache) 'Web Pack manifest did not require revalidation.'
    } finally {
        $webManifestResponse.Dispose()
        $webManifestRequest.Dispose()
    }
    $webPackRequest = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, "$webBase/data/web-pack-validation.json.gz")
    $webPackResponse = $client.Send($webPackRequest)
    try {
        Assert-True $webPackResponse.IsSuccessStatusCode 'Versioned Web Pack was not publicly readable.'
        Assert-True ($null -eq $webPackResponse.Content.Headers.ContentEncoding -or $webPackResponse.Content.Headers.ContentEncoding.Count -eq 0) 'Web Pack unexpectedly had Content-Encoding.'
        Assert-True ([string]$webPackResponse.Content.Headers.ContentType.MediaType -eq 'application/gzip') 'Web Pack MIME type was incorrect.'
    } finally {
        $webPackResponse.Dispose()
        $webPackRequest.Dispose()
    }

    $bootstrapOutput = Invoke-Compose run --rm --no-deps shared-map-server bootstrap-admin --display-name Phase8A_Admin --workspace-name Phase8A_Validation --invite-ttl 1h
    $bootstrapText = $bootstrapOutput -join "`n"
    $inviteMatch = [regex]::Match($bootstrapText, '(?m)^inviteToken=(esm_inv_\S+)\s*$')
    Assert-True $inviteMatch.Success 'Bootstrap did not return its one-time test invite.'
    $invite = $inviteMatch.Groups[1].Value

    $exchange = Send-ApiRequest POST "$httpsBase/api/v1/auth/exchange-invite" @{
        inviteToken = $invite
        deviceName = 'Phase8A validation client'
    }
    $invite = $null
    $token = [string]$exchange.Json.accessToken
    $workspaceId = [string]$exchange.Json.workspace.workspaceId
    Assert-True ($token.StartsWith('esm_dev_')) 'Invite exchange did not return a device token.'

    $marker = Send-ApiRequest POST "$httpsBase/api/v1/workspaces/$workspaceId/markers" @{
        systemId = 30000142
        name = 'PHASE8A RESTORE TEST'
        color = 'BLUE'
        tags = @('strategic')
        notes = 'Synthetic marker; removed with the isolated validation volume.'
    } $token ([Guid]::NewGuid().ToString())
    Assert-True ($marker.Json.version -eq 1) 'Synthetic Shared Marker was not created.'
    $validationMarkerId = [string]$marker.Json.markerId
    $snapshot = Send-ApiRequest GET "$httpsBase/api/v1/workspaces/$workspaceId/markers" $null $token
    Assert-True ($snapshot.Json.markers.Count -eq 1) 'Primary database marker snapshot was unexpected.'

    $routeHandoff = Send-ApiRequest POST "$httpsBase/api/v1/workspaces/$workspaceId/route-handoffs" @{
        type = 'NORMAL'
        originSystemId = 30000142
        waypointSystemIds = @()
        destinationSystemId = 30000144
        useAnsiblex = $false
        resolvedSystemIds = @(30000142, 30000144)
        resolvedEdges = @(@{
            fromSystemId = 30000142
            toSystemId = 30000144
            type = 'STARGATE'
        })
        mapMetadata = @{
            universeBuild = [string]$meta.Json.universeBuild
            plannerVersion = '1.8.0'
            webPackVersion = 'validation'
        }
    } $token ([Guid]::NewGuid().ToString())
    Assert-True ([bool]$routeHandoff.Json.routeHandoffId) 'Synthetic Route Handoff was not created.'
    $routeHandoffs = Send-ApiRequest GET "$httpsBase/api/v1/workspaces/$workspaceId/route-handoffs" $null $token
    Assert-True ($routeHandoffs.Json.routeHandoffs.Count -eq 1) 'Primary database Route Handoff snapshot was unexpected.'

    $auditCount = (Invoke-Compose exec --no-TTY postgres sh -c 'psql -U "$(cat /run/secrets/db_username)" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM audit_events;"' | Select-Object -First 1).Trim()
    Assert-True ([int]$auditCount -gt 0) 'Primary database contains no audit events.'

    foreach ($stamp in '20260115T000001Z', '20260215T000002Z', '20260315T000003Z') {
        $backupOutput = Invoke-Compose --profile ops run --rm --no-deps `
            --env SHARED_MAP_BACKUP_TIMESTAMP=$stamp `
            --env SHARED_MAP_BACKUP_DAILY_RETENTION=2 `
            --env SHARED_MAP_BACKUP_MONTHLY_RETENTION=1 `
            backup
        Assert-True (($backupOutput -join "`n") -match 'backup_status=success') "Backup failed for $stamp."
    }
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $stagingRoot 'daily') -Filter '*.dump.gpg').Count -eq 2) 'Daily retention did not keep exactly two validation artifacts.'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $stagingRoot 'monthly') -Filter '*.dump.gpg').Count -eq 1) 'Monthly retention did not keep exactly one validation artifact.'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $offsiteRoot 'daily') -Filter '*.dump.gpg').Count -eq 2) 'Off-site daily copy/retention failed.'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $offsiteRoot 'monthly') -Filter '*.dump.gpg').Count -eq 1) 'Off-site monthly copy/retention failed.'
    Assert-True (@(Get-ChildItem -LiteralPath $testRoot -Recurse -Filter '*.dump').Count -eq 0) 'A plaintext database dump escaped tmpfs.'

    $backupArtifact = Join-Path $offsiteRoot 'daily/eve-shared-map-20260315T000003Z.dump.gpg'
    $checksumArtifact = "$backupArtifact.sha256"
    Assert-True (Test-Path -LiteralPath $backupArtifact -PathType Leaf) 'Expected encrypted backup is missing.'
    $header = [IO.File]::ReadAllBytes($backupArtifact)[0..4]
    Assert-True ([Text.Encoding]::ASCII.GetString($header) -ne 'PGDMP') 'Backup artifact is not encrypted.'

    $restoreOutput = Invoke-Compose --profile ops run --rm --no-deps restore `
        --backup /backups/offsite/daily/eve-shared-map-20260315T000003Z.dump.gpg `
        --checksum /backups/offsite/daily/eve-shared-map-20260315T000003Z.dump.gpg.sha256 `
        --target-database phase8a_restore `
        --confirm-reset phase8a_restore
    $restoreText = $restoreOutput -join "`n"
    Assert-True ($restoreText -match 'restore_status=success') 'Isolated restore did not report success.'
    Assert-True ($restoreText -match 'restore_marker_count=1') 'Restored marker count is incorrect.'
    Assert-True ($restoreText -match 'restore_route_handoff_count=1') 'Restored Route Handoff count is incorrect.'
    Assert-True ($restoreText -match 'restore_audit_count=[1-9][0-9]*') 'Restored audit data is missing.'

    $networkName = "$projectName`_database"
    $dbUserFile = Join-Path $secretRoot 'server-database-user.txt'
    $dbPasswordFile = Join-Path $secretRoot 'server-database-password.txt'
    $pepperFile = Join-Path $secretRoot 'token-pepper.txt'
    $restoreContainerId = (Invoke-Docker run --detach `
        --name $restoreContainer `
        --network $networkName `
        --user 10001:10001 `
        --read-only `
        --tmpfs '/tmp/eve-shared-map:size=64m,mode=1777,uid=10001,gid=10001' `
        --cap-drop ALL `
        --security-opt no-new-privileges:true `
        --mount "type=bind,source=$dbUserFile,target=/run/secrets/db_username,readonly" `
        --mount "type=bind,source=$dbPasswordFile,target=/run/secrets/db_password,readonly" `
        --mount "type=bind,source=$pepperFile,target=/run/secrets/token_pepper,readonly" `
        --env SHARED_MAP_DATABASE_URL=jdbc:postgresql://postgres:5432/phase8a_restore `
        --env SHARED_MAP_DATABASE_USER_FILE=/run/secrets/db_username `
        --env SHARED_MAP_DATABASE_PASSWORD_FILE=/run/secrets/db_password `
        --env SHARED_MAP_TOKEN_PEPPER_FILE=/run/secrets/token_pepper `
        --env SHARED_MAP_ENVIRONMENT=validation `
        $ServerImage | Select-Object -First 1).Trim()
    Assert-True ([bool]$restoreContainerId) 'Isolated restored Server did not start.'
    $restoreHealthy = $false
    $lastRestoreHealthError = ''
    for ($index = 0; $index -lt 45; $index++) {
        try {
            $restoredHealthText = (Invoke-Docker exec $restoreContainer sh -c 'wget -q -O - http://127.0.0.1:8080/health') -join "`n"
            $restoredHealth = $restoredHealthText | ConvertFrom-Json
            if ($restoredHealth.status -eq 'ok') { $restoreHealthy = $true; break }
        } catch {
            $lastRestoreHealthError = $_.Exception.Message
            Start-Sleep -Seconds 1
        }
    }
    Assert-True $restoreHealthy "Isolated restored Server did not become healthy: $lastRestoreHealthError"
    $restoredSnapshotText = (Invoke-Docker exec `
        --env "TEST_TOKEN=$token" `
        --env "TEST_PATH=/api/v1/workspaces/$workspaceId/markers" `
        $restoreContainer `
        sh -c 'wget -q -O - --header="Authorization: Bearer $TEST_TOKEN" "http://127.0.0.1:8080$TEST_PATH"') -join "`n"
    $restoredSnapshot = $restoredSnapshotText | ConvertFrom-Json
    Assert-True ($restoredSnapshot.markers.Count -eq 1) 'Restored Server authentication/marker smoke failed.'
    $restoredRouteText = (Invoke-Docker exec `
        --env "TEST_TOKEN=$token" `
        --env "TEST_PATH=/api/v1/workspaces/$workspaceId/route-handoffs" `
        $restoreContainer `
        sh -c 'wget -q -O - --header="Authorization: Bearer $TEST_TOKEN" "http://127.0.0.1:8080$TEST_PATH"') -join "`n"
    $restoredRoutes = $restoredRouteText | ConvertFrom-Json
    Assert-True ($restoredRoutes.routeHandoffs.Count -eq 1) 'Restored Server authentication/Route Handoff smoke failed.'

    $null = Invoke-Compose restart postgres
    $null = Wait-Healthy postgres
    $databaseRecovered = $false
    for ($index = 0; $index -lt 30; $index++) {
        try {
            $recoveredHealth = Send-ApiRequest GET "$httpsBase/health"
            if ($recoveredHealth.Json.status -eq 'ok') { $databaseRecovered = $true; break }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    Assert-True $databaseRecovered 'Server did not recover after PostgreSQL restart.'

    $null = Invoke-Compose restart shared-map-server caddy
    $null = Wait-Healthy shared-map-server
    $null = Wait-Healthy caddy
    $postRestartSnapshot = Send-ApiRequest GET "$httpsBase/api/v1/workspaces/$workspaceId/markers" $null $token
    Assert-True ($postRestartSnapshot.Json.markers.Count -eq 1) 'HTTPS marker read failed after Server/Caddy restart.'
    $postRestartRoutes = Send-ApiRequest GET "$httpsBase/api/v1/workspaces/$workspaceId/route-handoffs" $null $token
    Assert-True ($postRestartRoutes.Json.routeHandoffs.Count -eq 1) 'HTTPS Route Handoff read failed after Server/Caddy restart.'

    $null = Send-ApiRequest DELETE "$httpsBase/api/v1/workspaces/$workspaceId/markers/${validationMarkerId}?expectedVersion=1" $null $token ([Guid]::NewGuid().ToString())
    $emptySnapshot = Send-ApiRequest GET "$httpsBase/api/v1/workspaces/$workspaceId/markers" $null $token
    Assert-True ($emptySnapshot.Json.markers.Count -eq 0) 'Synthetic restore marker cleanup failed before Map E2E.'

    $realMapE2e = $false
    if ($MapRepository) {
        $mapRoot = [IO.Path]::GetFullPath($MapRepository)
        $mapGradle = Join-Path $mapRoot 'gradlew.bat'
        Assert-True (Test-Path -LiteralPath $mapGradle -PathType Leaf) 'MapRepository does not contain gradlew.bat.'
        $e2eMember = Send-ApiRequest POST "$httpsBase/api/v1/workspaces/$workspaceId/members" @{
            displayName = 'Phase8A E2E Admin'
            role = 'ADMIN'
        } $token ([Guid]::NewGuid().ToString())
        $e2eMemberId = [string]$e2eMember.Json.memberId
        $e2eInviteResponse = Send-ApiRequest POST "$httpsBase/api/v1/workspaces/$workspaceId/members/$e2eMemberId/invites" @{
            expiresInHours = 1
        } $token ([Guid]::NewGuid().ToString())
        $e2eInvite = [string]$e2eInviteResponse.Json.inviteToken

        $null = Invoke-ValidationCompose up --detach --no-deps --force-recreate shared-map-server
        $validationServerId = Wait-Healthy shared-map-server

        $previousEnvironment = @{
            SHARED_MAP_INTEGRATION_INVITE = $env:SHARED_MAP_INTEGRATION_INVITE
            SHARED_MAP_INTEGRATION_SERVER = $env:SHARED_MAP_INTEGRATION_SERVER
            SHARED_MAP_INTEGRATION_DOCKER = $env:SHARED_MAP_INTEGRATION_DOCKER
            SHARED_MAP_INTEGRATION_CONTAINER = $env:SHARED_MAP_INTEGRATION_CONTAINER
        }
        try {
            $env:SHARED_MAP_INTEGRATION_INVITE = $e2eInvite
            $env:SHARED_MAP_INTEGRATION_SERVER = "http://127.0.0.1:$validationServerPort"
            $env:SHARED_MAP_INTEGRATION_DOCKER = $DockerBin
            $env:SHARED_MAP_INTEGRATION_CONTAINER = $validationServerId
            $e2eInvite = $null
            Push-Location $mapRoot
            try {
                & $mapGradle --no-daemon --console=plain :shared-client:jvmTest `
                    --tests dev.evestaticmapplanner.shared.integration.RealSharedMapServerIntegrationTest `
                    --rerun-tasks
                if ($LASTEXITCODE -ne 0) { throw 'Real Shared Map client E2E failed.' }
            } finally {
                Pop-Location
            }
            $realMapE2e = $true
        } finally {
            $env:SHARED_MAP_INTEGRATION_INVITE = $null
            foreach ($entry in $previousEnvironment.GetEnumerator()) {
                if ($null -eq $entry.Value) {
                    Remove-Item -LiteralPath "Env:$($entry.Key)" -ErrorAction SilentlyContinue
                } else {
                    Set-Item -LiteralPath "Env:$($entry.Key)" -Value $entry.Value
                }
            }
        }
    }

    $summary = [ordered]@{
        status = 'PASS'
        composeProject = $projectName
        serverImage = $ServerImage
        serverImageId = [string]$serverInspect.Image
        opsImage = $OpsImage
        postgresPrivate = $true
        serverPrivate = $true
        serverUser = $serverInspect.Config.User
        serverReadOnly = [bool]$serverInspect.HostConfig.ReadonlyRootfs
        secretMounts = 3
        caddyHttps = $true
        exactWebOriginCors = $true
        webStaticSite = $true
        webPackTransportContract = $true
        httpRedirect = $true
        health = 'ok'
        serverVersion = [string]$meta.Json.serverVersion
        protocol = [int]$meta.Json.protocolVersion
        universeBuild = [string]$meta.Json.universeBuild
        markerRoundTrip = $true
        routeHandoffRoundTrip = $true
        encryptedBackup = $true
        dailyRetention = 'configured=30, tested=2'
        monthlyRetention = 'configured=12, tested=1'
        isolatedRestore = $true
        restoredAuthentication = $true
        restoredMarkerCount = 1
        restoredRouteHandoffCount = 1
        restoredAuditCount = [int]([regex]::Match($restoreText, 'restore_audit_count=([0-9]+)').Groups[1].Value)
        postgresRestartRecovery = $true
        serverCaddyRestartRecovery = $true
        realMapClientE2e = $realMapE2e
    }
    $summary | ConvertTo-Json -Depth 4
} finally {
    $token = $null
    if ($client) { $client.Dispose() }
    try { $null = & $DockerBin rm -f $restoreContainer 2>$null } catch {}
    if (Test-Path -LiteralPath $envFile) {
        try { $null = & $DockerBin compose --project-name $projectName --env-file $envFile --file $composeFile down --volumes --remove-orphans 2>$null } catch {}
    }
    $resolvedRepo = [IO.Path]::GetFullPath($repoRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $resolvedTest = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedTest.StartsWith($resolvedRepo, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTest).StartsWith('.phase8a-validation-', [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedTest -Recurse -Force -ErrorAction SilentlyContinue
    }
}
