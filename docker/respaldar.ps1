# Suite DIGSA · respaldo de la base y de los archivos, juntos (Anexo 5, 1.23 y 3.6). Equivalente de docker/respaldar.sh para un
# servidor o equipo con Windows (PowerShell 5.1 o 7). Mismos archivos, mismas huellas y la misma bitácora.
#
# Uso, sin detener la aplicación:   powershell -NoProfile -ExecutionPolicy Bypass -File docker\respaldar.ps1
# Programarlo una vez al día como mínimo (Programador de tareas). Termina con código distinto de 0 si algo falla.
# El binario nunca pasa por una redirección de la consola (en PowerShell 5.1, «>» corrompe el .dump): se escribe dentro del
# contenedor y se copia con «docker compose cp».
# Variables opcionales: DIGSA_RESPALDOS, DIGSA_RESPALDO_MARCA, DIGSA_RESPALDO_SIN_PRUEBA, DIGSA_RESPALDO_RETENER_DIAS.
$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $PSScriptRoot
Set-Location $dir

$respaldos = if ($env:DIGSA_RESPALDOS) { $env:DIGSA_RESPALDOS } else { Join-Path $dir 'respaldos' }
$F = if ($env:DIGSA_RESPALDO_MARCA) { $env:DIGSA_RESPALDO_MARCA } else { Get-Date -Format 'yyyyMMdd_HHmm' }
$bitacora = Join-Path $respaldos 'bitacora_respaldos.csv'
$utf8 = New-Object System.Text.UTF8Encoding($false)
New-Item -ItemType Directory -Force -Path $respaldos | Out-Null
$script:version = ''

function Anotar([string]$archivos, [string]$tamanos, [string]$huellas, [string]$resultado) {
    if (-not (Test-Path $bitacora)) {
        [System.IO.File]::WriteAllText($bitacora, "fecha;operacion;version;archivos;tamanos;huellas;resultado`n", $utf8)
    }
    $linea = "{0};respaldo;{1};{2};{3};{4};{5}`n" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $script:version, $archivos, $tamanos, $huellas, $resultado
    [System.IO.File]::AppendAllText($bitacora, $linea, $utf8)
}

# Ejecuta docker con los argumentos dados; si termina con error, lanza la etapa como excepción
function Paso([string]$etapa) {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw $etapa }
}

function Huella([string]$ruta) { (Get-FileHash -Algorithm SHA256 -Path $ruta).Hash.ToLowerInvariant() }

try {
    if (-not (Test-Path 'docker-compose.yml')) { throw 'carpeta sin docker-compose.yml' }

    # En las cadenas para «sh -c» no van comillas dobles: PowerShell 5.1 las altera al pasarlas a docker
    Paso 'base no disponible' compose exec -T db sh -c 'pg_isready -q -U $POSTGRES_USER -d digsa'

    $contenedor = (& docker compose ps -q app)
    if ($contenedor) {
        $imagen = (& docker inspect --format '{{.Config.Image}}' $contenedor)
        $etiquetas = (& docker inspect --format '{{json .Config.Labels}}' $contenedor) | ConvertFrom-Json
        $revision = if ($etiquetas) { $etiquetas.'org.opencontainers.image.revision' } else { $null }
        $script:version = if ($revision) { "$imagen ($revision)" } else { "$imagen" }
    }

    Write-Host '==> 1. Base (pg_dump dentro del contenedor y copia afuera)'
    Paso 'copia de la base' compose exec -T db sh -c 'pg_dump -U $POSTGRES_USER -d digsa -Fc -Z 6 -f /tmp/digsa.dump && pg_restore -l /tmp/digsa.dump > /dev/null'
    Paso 'prueba del respaldo de la base' compose cp herramientas/verificar_restauracion.sql db:/tmp/verificar_restauracion.sql
    if (-not $env:DIGSA_RESPALDO_SIN_PRUEBA) {
        Write-Host '    restauración de prueba en una base temporal y conteo de filas'
        $prueba = 'set -e; dropdb -U $POSTGRES_USER --if-exists digsa_prueba_respaldo; ' +
            'createdb -U $POSTGRES_USER -O $POSTGRES_USER digsa_prueba_respaldo; ' +
            'pg_restore -U $POSTGRES_USER -d digsa_prueba_respaldo --exit-on-error --single-transaction /tmp/digsa.dump; ' +
            'psql -X -q -At -F '';'' -U $POSTGRES_USER -d digsa_prueba_respaldo -f /tmp/verificar_restauracion.sql -o /tmp/conteos.csv; ' +
            'dropdb -U $POSTGRES_USER digsa_prueba_respaldo'
        Paso 'prueba del respaldo de la base' compose exec -T db sh -c $prueba
    } else {
        Write-Host '    AVISO: sin restauración de prueba (DIGSA_RESPALDO_SIN_PRUEBA); conteos de la base en uso'
        Paso 'conteo de filas' compose exec -T db sh -c 'psql -X -q -At -F '';'' -U $POSTGRES_USER -d digsa -f /tmp/verificar_restauracion.sql -o /tmp/conteos.csv'
    }
    Paso 'copia de la base fuera del contenedor' compose cp db:/tmp/digsa.dump (Join-Path $respaldos "digsa_$F.dump")
    Paso 'copia de la base fuera del contenedor' compose cp db:/tmp/conteos.csv (Join-Path $respaldos "conteos_$F.csv")
    Paso 'limpieza' compose exec -T db rm -f /tmp/digsa.dump /tmp/conteos.csv /tmp/verificar_restauracion.sql

    Write-Host '==> 2. Archivos (con el usuario de la aplicación)'
    Paso 'aplicación no disponible para copiar los archivos' compose exec -T app tar -czf /tmp/archivos.tgz -C /datos archivos
    Paso 'copia de los archivos fuera del contenedor' compose cp app:/tmp/archivos.tgz (Join-Path $respaldos "archivos_$F.tgz")
    Paso 'limpieza' compose exec -T app rm -f /tmp/archivos.tgz

    Write-Host '==> 3. Huellas y bitácora de respaldos'
    $versionEnv = ''
    if (Test-Path '.env') {
        $m = Select-String -Path '.env' -Pattern '^DIGSA_VERSION=(.*)$' | Select-Object -First 1
        if ($m) { $versionEnv = $m.Matches[0].Groups[1].Value.Trim() }
    }
    $info = "fecha=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')`nversion=$($script:version)`nDIGSA_VERSION=$versionEnv`n"
    [System.IO.File]::WriteAllText((Join-Path $respaldos "respaldo_$F.info"), $info, $utf8)
    $nombres = @("digsa_$F.dump", "archivos_$F.tgz", "conteos_$F.csv", "respaldo_$F.info")
    # Mismo formato de sha256sum («huella  archivo», fin de línea LF): se verifica igual en Linux y en Windows
    $lineas = ($nombres | ForEach-Object { '{0}  {1}' -f (Huella (Join-Path $respaldos $_)), $_ }) -join "`n"
    [System.IO.File]::WriteAllText((Join-Path $respaldos "respaldo_$F.sha256"), "$lineas`n", $utf8)
    $tamDb = (Get-Item (Join-Path $respaldos "digsa_$F.dump")).Length
    $tamAr = (Get-Item (Join-Path $respaldos "archivos_$F.tgz")).Length
    Anotar "digsa_$F.dump archivos_$F.tgz" "$tamDb $tamAr" ("{0} {1}" -f (Huella (Join-Path $respaldos "digsa_$F.dump")), (Huella (Join-Path $respaldos "archivos_$F.tgz"))) 'ok'

    if ($env:DIGSA_RESPALDO_RETENER_DIAS) {
        $limite = (Get-Date).AddDays(-[int]$env:DIGSA_RESPALDO_RETENER_DIAS)
        Get-ChildItem $respaldos -File | Where-Object {
            $_.Name -match '^(digsa_.*\.dump|archivos_.*\.tgz|conteos_.*\.csv|respaldo_.*\.(info|sha256))$' -and $_.LastWriteTime -lt $limite
        } | ForEach-Object { Write-Host "    retención: se borra $($_.Name)"; Remove-Item $_.FullName -Force }
    }

    Write-Host "Respaldo $F listo en $respaldos (base $tamDb bytes, archivos $tamAr bytes)."
    Write-Host "Lleve digsa_$F.dump, archivos_$F.tgz, conteos_$F.csv, respaldo_$F.info y respaldo_$F.sha256 al medio de custodia."
    exit 0
} catch {
    $ErrorActionPreference = 'Continue'
    $etapa = $_.Exception.Message
    Write-Host "ERROR: el respaldo $F falló en la etapa «$etapa»." -ForegroundColor Red
    Anotar '' '' '' "falló: $etapa"
    & docker compose exec -T db rm -f /tmp/digsa.dump /tmp/conteos.csv | Out-Null
    & docker compose exec -T app rm -f /tmp/archivos.tgz | Out-Null
    exit 1
}
