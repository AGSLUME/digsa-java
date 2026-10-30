import { randomBytes } from 'node:crypto';
import { defineConfig, devices } from '@playwright/test';

/**
 * Pruebas de punta a punta (equivalente de tests/ui/*.js del PHP). Motores: Chromium (y los canales Chrome y Edge cuando están
 * instalados), Firefox y WebKit (motor de Safari). Anchos: celular, tableta y escritorio.
 *
 * El servidor es el arranque local (PostgreSQL embebido, correo a archivo, SPA en el mismo origen) con 1 minuto de inactividad
 * para poder probar el aviso y el cierre. Antes: `npm --prefix ../frontend run build`.
 *
 * Corre igual en Windows (equipo de desarrollo) y en Linux (pipeline: ci/e2e.sh).
 */

// Las fechas de las reglas («hoy», plazos, festivos) se calculan en Bogotá, también en un ejecutor en UTC. Los procesos de las
// pruebas y el servidor heredan esta variable.
process.env.TZ ??= 'America/Bogota';

// En el pipeline (CI definido) la contraseña del administrador de las pruebas es aleatoria en cada corrida. Los procesos de las
// pruebas la heredan por la variable (apoyo.ts la lee de ahí). La clave no lleva «:», que separa usuario y clave.
if (process.env.CI && !process.env.DIGSA_E2E_ADMIN) {
  process.env.DIGSA_E2E_ADMIN = `admin.e2e:E2e.${randomBytes(18).toString('base64url')}.9z`;
}
const ADMIN = process.env.DIGSA_E2E_ADMIN ?? 'admin.e2e:Admin.E2E.Prueba.2026';

const canales = (process.env.DIGSA_E2E_CANALES ?? '').split(',').filter(Boolean);

// Módulo Correos · C4: DIGSA_E2E_CORREO=mailpit arranca el servidor con el perfil «mailpit» (SMTP a Mailpit en localhost:1025; solo
// para pruebas/mailpit.spec.ts, docs/guia_mailpit.md). Sin la variable, el correo va a archivo como siempre.
const CORREO_MAILPIT = process.env.DIGSA_E2E_CORREO === 'mailpit' ? ' -Ddigsa.local.correo=mailpit' : '';

// Puertos de la aplicación y del PostgreSQL embebido. Varias corridas a la vez en el mismo equipo usan puertos distintos
// (DIGSA_E2E_PUERTO / DIGSA_E2E_PGPUERTO); la limpieza solo detiene lo que escuche en los puertos de esta corrida.
const PUERTO = Number(process.env.DIGSA_E2E_PUERTO ?? 8080);
const PGPUERTO = Number(process.env.DIGSA_E2E_PGPUERTO ?? 54329);
process.env.DIGSA_E2E_PUERTO = String(PUERTO);
process.env.DIGSA_E2E_PGPUERTO = String(PGPUERTO);

// Arranque del servidor: mvnw.cmd en Windows, mvnw en Linux y macOS. JAVA_HOME sale del entorno; en Windows, si no está
// definido, se usa la instalación portátil de %LOCALAPPDATA% (equipo de desarrollo sin permisos de administrador).
const windows = process.platform === 'win32';
const mvnw = windows ? '..\\backend\\mvnw.cmd' : '../backend/mvnw';
const javaHome =
  process.env.JAVA_HOME ?? (windows && process.env.LOCALAPPDATA ? `${process.env.LOCALAPPDATA}/Programs/jdk21` : undefined);
// MAVEN_OPTS pasa al servidor: con -Dmaven.repo.local propio, el servidor usa el módulo reglas instalado por ESTA copia de trabajo
// y no el de otra sesión que haya instalado el suyo en el ~/.m2 compartido
const entornoServidor: Record<string, string> = {
  TZ: 'America/Bogota',
  ...(javaHome ? { JAVA_HOME: javaHome } : {}),
  ...(process.env.MAVEN_OPTS ? { MAVEN_OPTS: process.env.MAVEN_OPTS } : {}),
};

export default defineConfig({
  testDir: './pruebas',
  globalTeardown: './limpieza.ts',
  timeout: 120_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'test-results/junit.xml' }]],
  use: {
    baseURL: `http://localhost:${PUERTO}`,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    locale: 'es-CO',
    timezoneId: 'America/Bogota',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    ...canales.map((c) => ({ name: c, use: { ...devices['Desktop Chrome'], channel: c } })),
  ],
  webServer: {
    command:
      `${mvnw} -q -f ../backend/pom.xml -pl app spring-boot:test-run ` +
      `-Dspring-boot.run.main-class=co.com.agsamericas.digsa.DigsaLocal ` +
      `"-Dspring-boot.run.jvmArguments=-Duser.timezone=America/Bogota -Ddigsa.local.admin=${ADMIN} ` +
      `-Ddigsa.local.puerto=${PUERTO} -Ddigsa.local.pgpuerto=${PGPUERTO} ` +
      `-Ddigsa.local.raiz=${process.cwd().replace(/\\/g, '/')}/..${CORREO_MAILPIT}" ` +
      `"-Dspring-boot.run.arguments=--digsa.sesion-minutos=1 --digsa.mfa.codigos-por-hora=1000 --digsa.opl.codigos-por-hora-ip=1000"`,
    url: `http://localhost:${PUERTO}/actuator/health`,
    timeout: 240_000,
    reuseExistingServer: false,
    env: entornoServidor,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
