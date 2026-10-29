package co.com.agsamericas.digsa.analitica;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del módulo de analítica (prefijo {@code digsa.analitica}; clase propia para no tocar ConfiguracionDigsa). Los valores
 * por defecto están en application.yml con {@code ${DIGSA_*:defecto}}; aquí solo se acotan.
 *
 * @param claveSeudonimo       clave del HMAC-SHA256 del paciente (DIGSA_ANALITICA_CLAVE_SEUDONIMO). Nunca en el repositorio ni en el
 *                             registro. En producción es obligatoria: sin ella la aplicación no arranca (ValidacionArranqueAnalitica);
 *                             en los demás entornos salvo local, sin ella la aplicación arranca pero rechaza todo cargue (nunca una
 *                             clave aleatoria: partiría a cada paciente en dos al reiniciar).
 *                             <b>Rotación:</b> cambiarla parte a cada paciente en dos seudónimos (cohortes y pacientes distintos dejan
 *                             de cuadrar con lo ya cargado). No se rota durante el contrato salvo incidente; si se rota, se vuelven a
 *                             cargar todos los insumos con la clave nueva (los originales del almacén sirven mientras estén vigentes) y
 *                             se borran los hechos con la clave anterior. La clave vive en Key Vault y se cambia allí.
 * @param parteMaxMb           tamaño máximo de cada parte de la subida (50 MB)
 * @param archivoMaxMb         tamaño máximo del archivo completo (y del contenido descomprimido de .gz y .zip)
 * @param conservarDias        días que se conserva el archivo original en el almacén (365; A4 L382)
 * @param umbralMalFormadas    % de filas con otro número de campos que rechaza el archivo completo (1 %)
 * @param loteValidacion       registros por lote de la validación por conjuntos
 * @param maxRegistrosLote     registros por lote del servicio web del OPL
 * @param horasAbandono        horas sin actividad tras las que un cargue en subida, mapeo o esperando lotes vence y se descarta (24)
 * @param celdaMaxKb           tope de una celda del CSV (64 KB): más es una comilla sin cerrar o un archivo dañado (rechazo estructural)
 * @param registroMaxKb        tope de un registro del CSV (1 MB), por la misma razón
 * @param cuerpoWsMaxMb        tope del cuerpo de un lote del servicio web (64 MB; más responde 413)
 * @param textosXlsxMaxMb      tope de la tabla de textos compartidos de un xlsx sin comprimir (256 MB; se lee completa en memoria)
 * @param carguesAbiertosMax   cargues abiertos (subiendo o mapeo) por usuario (3)
 */
@ConfigurationProperties(prefix = "digsa.analitica")
public record PropiedadesAnalitica(String claveSeudonimo, Integer parteMaxMb, Integer archivoMaxMb, Integer conservarDias,
                                   BigDecimal umbralMalFormadas, Integer loteValidacion, Integer maxRegistrosLote, Integer horasAbandono,
                                   Integer celdaMaxKb, Integer registroMaxKb, Integer cuerpoWsMaxMb, Integer textosXlsxMaxMb,
                                   Integer carguesAbiertosMax) {

    /** Clave fija de desarrollo, solo con digsa.entorno=local y sin variable: no es secreto (los datos locales son sintéticos). */
    public static final String CLAVE_LOCAL = "clave-local-de-desarrollo-no-secreta";

    /** Solo la clave (pruebas de unidad); lo demás con sus valores por defecto. */
    public static PropiedadesAnalitica conClave(String clave) {
        return new PropiedadesAnalitica(clave, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public long parteMaxBytes() {
        return (parteMaxMb == null || parteMaxMb < 1 ? 50L : Math.min(parteMaxMb, 100)) * 1024 * 1024;
    }

    public long archivoMaxBytes() {
        return (archivoMaxMb == null || archivoMaxMb < 1 ? 4096L : archivoMaxMb) * 1024 * 1024;
    }

    public int diasConservar() {
        return conservarDias == null || conservarDias < 1 ? 365 : conservarDias;
    }

    public BigDecimal umbral() {
        return umbralMalFormadas == null || umbralMalFormadas.signum() < 0 ? BigDecimal.ONE : umbralMalFormadas;
    }

    public int registrosPorLoteValidacion() {
        return loteValidacion == null || loteValidacion < 1000 ? 250_000 : loteValidacion;
    }

    public int registrosPorLoteWs() {
        return maxRegistrosLote == null || maxRegistrosLote < 1 ? 50_000 : maxRegistrosLote;
    }

    public int horasSinActividad() {
        return horasAbandono == null || horasAbandono < 1 ? 24 : horasAbandono;
    }

    /** Tope de una celda en caracteres. */
    public int celdaMax() {
        return (celdaMaxKb == null || celdaMaxKb < 1 ? 64 : celdaMaxKb) * 1024;
    }

    /** Tope de un registro en caracteres. */
    public int registroMax() {
        return (registroMaxKb == null || registroMaxKb < 1 ? 1024 : registroMaxKb) * 1024;
    }

    public long cuerpoWsMaxBytes() {
        return (cuerpoWsMaxMb == null || cuerpoWsMaxMb < 1 ? 64L : cuerpoWsMaxMb) * 1024 * 1024;
    }

    public long textosXlsxMaxBytes() {
        return (textosXlsxMaxMb == null || textosXlsxMaxMb < 1 ? 256L : textosXlsxMaxMb) * 1024 * 1024;
    }

    public int maxCarguesAbiertos() {
        return carguesAbiertosMax == null || carguesAbiertosMax < 1 ? 3 : carguesAbiertosMax;
    }

    public boolean tieneClave() {
        return claveSeudonimo != null && !claveSeudonimo.isBlank();
    }
}
