// Suite DIGSA · antes de las pruebas de punta a punta: detiene lo que quede escuchando en el puerto de la aplicación y en el del
// PostgreSQL embebido de una corrida anterior (DIGSA_E2E_PUERTO / DIGSA_E2E_PGPUERTO; por defecto 8080 y 54329). Multiplataforma.
// En el pipeline (CI definido) no hace nada: el ejecutor es nuevo en cada corrida.
import { execSync } from 'node:child_process';

const PUERTOS = [Number(process.env.DIGSA_E2E_PUERTO ?? 8080), Number(process.env.DIGSA_E2E_PGPUERTO ?? 54329)];

function silencioso(comando) {
  try {
    return execSync(comando, { stdio: ['ignore', 'pipe', 'ignore'] }).toString();
  } catch {
    return '';
  }
}

export function limpiarPuertos() {
  if (process.env.CI || process.env.DIGSA_E2E_SIN_LIMPIEZA) return;
  if (process.platform === 'win32') {
    const ps =
      `foreach ($p in ${PUERTOS.join(', ')}) { Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | ` +
      'Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { try { Stop-Process -Id $_ -Force -ErrorAction Stop } catch {} } }';
    silencioso(`powershell -NoProfile -NonInteractive -Command "${ps}"`);
    return;
  }
  for (const puerto of PUERTOS) {
    // lsof en Linux y macOS; si no está, fuser (psmisc)
    const pids = silencioso(`lsof -t -iTCP:${puerto} -sTCP:LISTEN`).split(/\s+/).filter(Boolean);
    if (pids.length) {
      for (const pid of new Set(pids)) {
        try {
          process.kill(Number(pid), 'SIGKILL');
        } catch {
          // ya terminó o no es de este usuario
        }
      }
    } else {
      silencioso(`fuser -k ${puerto}/tcp`);
    }
  }
}

limpiarPuertos();
