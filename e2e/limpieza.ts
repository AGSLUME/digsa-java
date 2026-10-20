import { execSync } from 'node:child_process';

/**
 * Antes y después de cada corrida: detiene lo que quede escuchando en el puerto de la aplicación (8080) y en el del PostgreSQL
 * embebido (54329). En Windows, Playwright cierra el proceso de Maven pero no a sus hijos (la JVM y PostgreSQL).
 */
export default function limpieza(): void {
  if (process.platform !== 'win32' || process.env.DIGSA_E2E_SIN_LIMPIEZA) return;
  const ps =
    "foreach ($p in 8080, 54329) { Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | " +
    'Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { try { Stop-Process -Id $_ -Force -ErrorAction Stop } catch {} } }';
  try {
    execSync(`powershell -NoProfile -NonInteractive -Command "${ps}"`, { stdio: 'ignore' });
  } catch {
    // nada que detener
  }
}
