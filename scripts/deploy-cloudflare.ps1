param(
  [string]$ProjectName = "v-core-saas",
  [string]$EnvPath = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

function Import-EnvFile {
  param([string]$Path)

  if (-not (Test-Path $Path)) {
    return $false
  }

  Get-Content $Path | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) {
      return
    }

    $parts = $line.Split("=", 2)
    $name = $parts[0].Trim()
    $value = $parts[1].Trim().Trim('"').Trim("'")

    if ($name) {
      [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
  }

  return $true
}

if (-not $EnvPath) {
  $localEnv = Join-Path $Root ".env"
  $parentEnv = Join-Path (Split-Path -Parent $Root) ".env"
  $EnvPath = if (Test-Path $localEnv) { $localEnv } else { $parentEnv }
}

if (-not (Import-EnvFile -Path $EnvPath)) {
  throw "No .env file found. Checked: $EnvPath"
}

if (-not $env:CLOUDFLARE_ACCOUNT_ID -and $env:ACCOUNT_ID) {
  $env:CLOUDFLARE_ACCOUNT_ID = $env:ACCOUNT_ID
}

if (-not $env:CLOUDFLARE_API_TOKEN -and $env:API_TOKEN) {
  $env:CLOUDFLARE_API_TOKEN = $env:API_TOKEN
}

if (-not $env:CLOUDFLARE_ACCOUNT_ID) {
  throw "Missing ACCOUNT_ID or CLOUDFLARE_ACCOUNT_ID in .env"
}

if (-not $env:CLOUDFLARE_API_TOKEN) {
  throw "Missing API_TOKEN or CLOUDFLARE_API_TOKEN in .env"
}

Push-Location $Root
try {
  npm run build

  $projectsJson = npx wrangler pages project list --json
  if ($LASTEXITCODE -ne 0) {
    throw "Unable to list Cloudflare Pages projects"
  }

  $projects = $projectsJson | ConvertFrom-Json
  $exists = $false

  foreach ($project in $projects) {
    $name = if ($project.name) { $project.name } else { $project."Project Name" }
    if ($name -eq $ProjectName) {
      $exists = $true
      break
    }
  }

  if (-not $exists) {
    npx wrangler pages project create $ProjectName --production-branch main --compatibility-date 2026-07-23
    if ($LASTEXITCODE -ne 0) {
      throw "Unable to create Cloudflare Pages project $ProjectName"
    }
  }

  npx wrangler pages deploy out --project-name $ProjectName --branch main --commit-dirty true
  if ($LASTEXITCODE -ne 0) {
    throw "Cloudflare Pages deploy failed"
  }
}
finally {
  Pop-Location
}
