package co.com.agsamericas.digsa.analitica.prediccion;

import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la predicción (prefijo {@code digsa.analitica.prediccion}; clase propia del módulo). Valores por defecto en
 * application.yml con {@code ${DIGSA_*:defecto}}; fuera de rango la aplicación no arranca.
 *
 * @param entrenarAlAplicar entrena gasto, dispensaciones y pacientes a nivel nacional y por Fuerza al aplicar un cargue que completa un
 *                          mes (en segundo plano, después de confirmar: el cargue no espera ni falla por esto). Las pruebas que no son de
 *                          predicción lo apagan.
 * @param horizonte         meses proyectados por defecto (6 a 24; A4 §11.1 f: mínimo 6)
 * @param porPagina         entrenamientos por página en la bitácora del tablero
 */
@ConfigurationProperties(prefix = "digsa.analitica.prediccion")
public record PropiedadesPrediccion(Boolean entrenarAlAplicar, Integer horizonte, Integer porPagina) {

    public PropiedadesPrediccion {
        rango("horizonte", horizonte, AnaliticaPrediccion.HORIZONTE_DEFECTO, AnaliticaPrediccion.HORIZONTE_MAXIMO);
        rango("por-pagina", porPagina, 5, 200);
    }

    private static void rango(String nombre, Integer v, int min, int max) {
        if (v != null && (v < min || v > max)) {
            throw new IllegalArgumentException("digsa.analitica.prediccion." + nombre + " = " + v + " está fuera de rango (" + min + " a " + max + ")");
        }
    }

    public boolean entrenar() {
        return entrenarAlAplicar == null || entrenarAlAplicar;
    }

    public int horizontePorDefecto() {
        return horizonte == null ? AnaliticaPrediccion.HORIZONTE_DEFECTO : horizonte;
    }

    public int pagina() {
        return porPagina == null ? 20 : porPagina;
    }
}
