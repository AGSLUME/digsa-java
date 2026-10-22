# Suite DIGSA · arranque local en Docker (este equipo). Uso: clic derecho > «Ejecutar con PowerShell», o iniciar-local.bat.
# - Comprueba que Docker esté corriendo.
# - La primera vez crea el .env con claves aleatorias (no se versiona ni sale del equipo) y pide el correo del administrador.
# - Construye la imagen, levanta la base y la aplicación, y abre el navegador.
$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
Set-Location $raiz

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host 'No se encontró Docker. Instale Docker Desktop o Rancher Desktop (docs/guia_docker_local.md, paso 1).' -ForegroundColor Red
    exit 1
}
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Docker está instalado pero no está corriendo: abra Docker Desktop (o Rancher Desktop), espere a que diga «running» y vuelva a ejecutar.' -ForegroundColor Red
    exit 1
}

function Clave-Aleatoria {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    # Solo letras y números: sin caracteres que el .env o la URL de conexión interpreten
    return (([Convert]::ToBase64String($bytes)) -replace '[^A-Za-z0-9]', '').Substring(0, 28)
}

$primeraVez = -not (Test-Path '.env')
if ($primeraVez) {
    $texto = Get-Content '.env.example' -Raw -Encoding UTF8
    $texto = $texto -replace '(?m)^DIGSA_DB_DUENO_CLAVE=.*$', ('DIGSA_DB_DUENO_CLAVE=' + (Clave-Aleatoria))
    $texto = $texto -replace '(?m)^DIGSA_DB_CLAVE=.*$', ('DIGSA_DB_CLAVE=' + (Clave-Aleatoria))
    # UTF-8 sin BOM: docker compose no lee bien la primera línea con BOM
    [System.IO.File]::WriteAllText((Join-Path $raiz '.env'), $texto, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'Se creó el archivo .env con claves aleatorias para la base (guárdelo: sin él no se abre la base de este equipo).' -ForegroundColor Green
}

Write-Host 'Construyendo y levantando (la primera vez tarda varios minutos: descarga Java, Node y PostgreSQL)...'
docker compose up -d --build
if ($LASTEXITCODE -ne 0) {
    Write-Host 'No se pudo levantar. Revise el mensaje de arriba y la guía (docs/guia_docker_local.md, «Problemas comunes»).' -ForegroundColor Red
    exit 1
}

Write-Host 'Esperando a que la aplicación responda...'
$lista = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 'http://localhost:8080/actuator/health'
        if ($r.StatusCode -eq 200) { $lista = $true; break }
    } catch { }
    Start-Sleep -Seconds 3
}
if (-not $lista) {
    Write-Host 'La aplicación no respondió en 3 minutos. Vea el log con: docker compose logs app --tail 100' -ForegroundColor Yellow
    exit 1
}

$admins = docker compose exec -T db sh -c 'psql -tA -U "$POSTGRES_USER" -d digsa -c "SELECT count(*) FROM usuarios"' 2>$null
if ($primeraVez -or ($admins -and $admins.Trim() -eq '0')) {
    $correo = Read-Host 'Correo del primer administrador (ej. su correo de AGS)'
    $nombre = Read-Host 'Nombre del administrador'
    $usuario = Read-Host 'Usuario para ingresar (ej. admin)'
    docker compose run --rm app "--digsa.crear-admin.correo=$correo" "--digsa.crear-admin.nombre=$nombre" "--digsa.crear-admin.usuario=$usuario" |
        Select-String -Pattern 'definir|contrase|http' | ForEach-Object { Write-Host $_.Line -ForegroundColor Cyan }
    Write-Host 'Abra el enlace de arriba («definir la contraseña») en el navegador para poner la contraseña del administrador.' -ForegroundColor Green
}

Write-Host 'Lista: http://localhost:8080' -ForegroundColor Green
Start-Process 'http://localhost:8080'
