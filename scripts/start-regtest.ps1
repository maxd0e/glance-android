param(
    [switch]$Reset
)

$composeFile = Join-Path $PSScriptRoot "..\docker\regtest\docker-compose.yml"
if ($Reset) {
    docker compose -f $composeFile down --volumes
}
docker compose -f $composeFile up --detach --wait
