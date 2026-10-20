import { defineConfig, devices } from '@playwright/test';

/**
 * Pruebas de punta a punta (equivalente de tests/ui/*.js del PHP). Motores: Chromium (y los canales Chrome y Edge cuando están
 * instalados), Firefox y WebKit (motor de Safari). Anchos: celular, tableta y escritorio.
 *
 * El servidor es el arranque local (PostgreSQL embebido, correo a archivo, SPA en el mismo origen) con 1 minuto de inactividad
 * para poder probar el aviso y el cierre. Antes: `npm --prefix ../frontend run build`.
 */
const ADMIN = process.env.DIGSA_E2E_ADMIN ?? 'admin.e2e:Admin.E2E.Prueba.2026';

const canales = (process.env.DIGSA_E2E_CANALES ?? '').split(',').filter(Boolean);

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
    baseURL: 'http://localhost:8080',
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
      `..\\backend\\mvnw.cmd -q -f ../backend/pom.xml -pl app spring-boot:test-run ` +
      `-Dspring-boot.run.main-class=co.com.agsamericas.digsa.DigsaLocal ` +
      `"-Dspring-boot.run.jvmArguments=-Ddigsa.local.admin=${ADMIN} -Ddigsa.local.raiz=${process.cwd().replace(/\\/g, '/')}/.." ` +
      `"-Dspring-boot.run.arguments=--digsa.sesion-minutos=1 --digsa.mfa.codigos-por-hora=1000"`,
    url: 'http://localhost:8080/actuator/health',
    timeout: 240_000,
    reuseExistingServer: false,
    env: { JAVA_HOME: process.env.JAVA_HOME ?? `${process.env.LOCALAPPDATA}/Programs/jdk21` },
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
