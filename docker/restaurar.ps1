# Suite DIGSA · restauración de un respaldo (base y archivos, juntos). Equivalente de docker/restaurar.sh para Windows
# (PowerShell 5.1 o 7). Mismos pasos y mismas comprobaciones.
#
# Uso, con la MISMA versión de la imagen del respaldo o una posterior:
#   powershell -NoProfile -ExecutionPolicy Bypass -File docker\restaurar.ps1 20261122_0100          # pide confirmación
#   powershell -NoProfile -ExecutionPolicy Bypass -File docker\restaurar.ps1 20261122_0100 -Si      # sin preguntar
# Los archivos del respaldo deben estar en respaldos\ (o en DIGSA_RESPALDOS).
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9_]+$')][string]$F,
    [switch]$Si
)
$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $PSScriptRoot
Set-Location $dir

$respaldos = if ($env:DIGSA_RESPALDOS) { $env:DIGSA_RESPALDOS } else { Join-Path $dir 'respaldos' }
$bitacora = Join-Path $respaldos 'bitacora_respaldos.csv'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Leer-Version([string]$archivo) {
    if (-not (Test-Path $archivo)) { return '' }
    $m = Select-String -Path $archivo -Pattern '^DIGSA_VERSION=(.*)$' | Select-Object -First 1
    if ($m) { return $m.Matches[0].Groups[1].Value.Trim() } else { return '' }
}
$versionActual = Leer-Version '.env'

function Anotar([string]$resultado) {
    if (-not (Test-Path $bitacora)) {
        [System.IO.File]::WriteAllText($bitacora, "fecha;operacion;version;archivos;tamanos;huellas;resultado`n", $utf8)
    }
    $linea = "{0};restauracion;{1};digsa_{2}.dump archivos_{2}.tgz;;;{3}`n" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $versionActual, $F, $resultado
    [System.IO.File]::AppendAllText($bitacora, $linea, $utf8)
}

function Paso([string]$etapa) {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw $etapa }
}

try {
    Write-Host "==> 1. Huellas del respaldo $F"
    foreach ($a in @("digsa_$F.dump", "archivos_$F.tgz", "respaldo_$F.sha256")) {
        if (-not (Test-Path (Join-Path $respaldos $a))) { throw "falta $a" }
    }
    foreach ($linea in [System.IO.File]::ReadAllLines((Join-Path $respaldos "respaldo_$F.sha256"))) {
        if (-not $linea.Trim()) { continue }
        $esperada, $nombre = $linea -split '\s+', 2
        $nombre = $nombre.TrimStart('*').Trim()
        $real = (Get-FileHash -Algorithm SHA256 -Path (Join-Path $respaldos $nombre)).Hash.ToLowerInvariant()
        if ($real -ne $esperada.ToLowerInvariant()) { throw "huella distinta en $nombre" }
        Write-Host "    ${nombre}: OK"
    }

    $versionRespaldo = Leer-Version (Join-Path $respaldos "respaldo_$F.info")
    Write-Host "    respaldo hecho con la versión «$versionRespaldo»; este servidor tiene «$versionActual»"
    $patron = '^[0-9]+\.[0-9]+\.[0-9]+$'
    if (($versionRespaldo -match $patron) -and ($versionActual -match $patron) -and ([version]$versionActual -lt [version]$versionRespaldo)) {
        throw "la imagen ($versionActual) es más vieja que el respaldo ($versionRespaldo): suba antes a la $versionRespaldo o posterior"
    }

    if (-not $Si) {
        $r = Read-Host "Se REEMPLAZAN la base y los archivos de este servidor por el respaldo $F. Escriba «si» para seguir"
        if ($r -ne 'si') { Write-Host 'Cancelado: no se cambió nada.'; exit 1 }
    }

    Write-Host '==> 2. Se detiene la aplicación (nadie escribe)'
    Paso 'no se pudo detener la aplicación' compose stop app
    Paso 'base no disponible' compose up -d --wait db
    $rol = (& docker compose exec -T db sh -c 'psql -X -q -At -U $POSTGRES_USER -d postgres -c ''SELECT rolname FROM pg_roles'' | grep -cx $DIGSA_DB_USUARIO')
    if ("$rol".Trim() -ne '1') { throw 'no existe el rol de la aplicación (DIGSA_DB_USUARIO): ver herramientas/permisos_rol_app.sql' }

    Write-Host '==> 3. Base: vacía y pg_restore en una sola transacción'
    Paso 'restauración de la base' compose cp (Join-Path $respaldos "digsa_$F.dump") db:/tmp/digsa.dump
    Paso 'restauración de la base' compose exec -T db sh -c ('set -e; dropdb -U $POSTGRES_USER --if-exists --force digsa; ' +
        'createdb -U $POSTGRES_USER -O $POSTGRES_USER digsa; ' +
        'pg_restore -U $POSTGRES_USER -d digsa --exit-on-error --single-transaction /tmp/digsa.dump; rm -f /tmp/digsa.dump')
    # El permiso de conexión a la base no viaja en el formato propio de pg_dump: se vuelve a dar
    Paso 'permisos del rol de la aplicación' compose exec -T db sh -c ('printf ''GRANT CONNECT ON DATABASE digsa TO %s; GRANT USAGE ON SCHEMA public TO %s;'' ' +
        '$DIGSA_DB_USUARIO $DIGSA_DB_USUARIO | psql -X -q -v ON_ERROR_STOP=1 -U $POSTGRES_USER -d digsa')

    Write-Host '==> 4. Filas por tabla frente a las del respaldo'
    Paso 'conteos' compose cp herramientas/verificar_restauracion.sql db:/tmp/verificar_restauracion.sql
    Paso 'conteos' compose exec -T db sh -c 'psql -X -q -At -F '';'' -U $POSTGRES_USER -d digsa -f /tmp/verificar_restauracion.sql -o /tmp/conteos.csv'
    Paso 'conteos' compose cp db:/tmp/conteos.csv (Join-Path $respaldos "conteos_restaurado_$F.csv")
    Paso 'conteos' compose exec -T db rm -f /tmp/conteos.csv /tmp/verificar_restauracion.sql
    $conteosRespaldo = Join-Path $respaldos "conteos_$F.csv"
    if (Test-Path $conteosRespaldo) {
        $antes = [System.IO.File]::ReadAllLines($conteosRespaldo) | Where-Object { $_ }
        $despues = [System.IO.File]::ReadAllLines((Join-Path $respaldos "conteos_restaurado_$F.csv")) | Where-Object { $_ }
        $diferencias = Compare-Object $antes $despues
        if ($diferencias) {
            $diferencias | ForEach-Object { Write-Host ("    {0} {1}" -f $_.SideIndicator, $_.InputObject) }
            throw 'conteos distintos a los del respaldo'
        }
        Write-Host "    $($antes.Count) tablas con las mismas filas que el respaldo"
    } else {
        Write-Host "    AVISO: el respaldo no trae conteos_$F.csv; no se pudo comparar"
    }

    Write-Host '==> 5. Archivos (se vacía /datos/archivos y se extrae con el usuario de la aplicación)'
    Paso 'restauración de los archivos' compose run --rm --no-deps -T -v "${respaldos}:/respaldos:ro" --entrypoint sh app `
        -c "find /datos/archivos -mindepth 1 -delete && tar -xzf /respaldos/archivos_$F.tgz -C /datos"

    Write-Host '==> 6. Arranque de la aplicación (Flyway valida; si la imagen es más nueva, migra)'
    $espera = if ($env:DIGSA_ESPERA_SEGUNDOS) { $env:DIGSA_ESPERA_SEGUNDOS } else { '300' }
    Paso 'la aplicación no arrancó sana' compose up -d --no-build --wait --wait-timeout $espera app

    Write-Host '==> 7. Cada archivo de la base existe y su SHA-256 coincide'
    $consulta = 'psql -X -q -At -U $POSTGRES_USER -d digsa -o /tmp/huellas_archivos.txt -c ' +
        '''SELECT sha256 || chr(32) || chr(32) || ruta FROM archivos WHERE eliminado IS NULL UNION ALL SELECT sha256 || chr(32) || chr(32) || ruta FROM opl_soportes ORDER BY 1'''
    Paso 'archivos faltantes o con huella distinta' compose exec -T db sh -c $consulta
    $lista = Join-Path $env:TEMP "huellas_archivos_$F.txt"
    Paso 'archivos faltantes o con huella distinta' compose cp db:/tmp/huellas_archivos.txt $lista
    Paso 'limpieza' compose exec -T db rm -f /tmp/huellas_archivos.txt
    $total = @([System.IO.File]::ReadAllLines($lista) | Where-Object { $_ }).Count
    if ($total -gt 0) {
        Paso 'archivos faltantes o con huella distinta' compose cp $lista app:/tmp/huellas_archivos.txt
        Paso 'archivos faltantes o con huella distinta' compose exec -T app sh -c 'cd /datos/archivos && sha256sum -c --quiet /tmp/huellas_archivos.txt; r=$?; rm -f /tmp/huellas_archivos.txt; exit $r'
    }
    Remove-Item $lista -Force
    Write-Host "    $total archivos verificados"
    if ($env:DIGSA_VERIFICAR_CON_APP) {
        Paso 'verificación de archivos de la aplicación' compose run --rm --no-deps app --digsa.verificar-archivos
    }

    Anotar 'ok'
    Write-Host "Restauración de $F terminada: base, $total archivos y aplicación en servicio. Ingrese con un usuario para confirmar."
    exit 0
} catch {
    $ErrorActionPreference = 'Continue'
    $etapa = $_.Exception.Message
    Write-Host "ERROR: la restauración de $F falló en la etapa «$etapa». Si la aplicación ya se había detenido, sigue detenida hasta resolverlo." -ForegroundColor Red
    Anotar "falló: $etapa"
    exit 1
}
