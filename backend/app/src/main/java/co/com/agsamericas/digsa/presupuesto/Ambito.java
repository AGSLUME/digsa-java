package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.Map;
import java.util.Set;

/**
 * Qué líneas del presupuesto ve el usuario (spec «Acceso y alcance»; Anexo 5 3.7): DIGSA, Coordinador y Administrador todo; Analista todo
 * en consulta (duda P.7: en esta rama Alcance.de le da Ninguno); Sanidad su fuerza (incluida su parte de HOMIL); ESM su punto (las líneas
 * de un centralizador traen la ejecución de sus satélites). Cualquier otro alcance no ve nada: falla cerrado.
 *
 * @param condicion SQL sobre el alias «l» de presupuesto_lineas
 */
record Ambito(String condicion, Map<String, Object> parametros, String descripcion) {

    static Ambito de(Alcance alcance, Set<Perfil> perfiles) {
        Alcance a = alcance instanceof Alcance.Ninguno && perfiles.contains(Perfil.ANALISTA) ? new Alcance.Todo() : alcance;
        return switch (a) {
            case Alcance.Todo t -> new Ambito("TRUE", Map.of(), "Nacional");
            case Alcance.Fuerza f -> new Ambito("l.fuerza = :ambito_fuerza", Map.of("ambito_fuerza", f.codigo()), "Fuerza " + f.codigo());
            case Alcance.Esm e -> new Ambito("l.punto_id = :ambito_punto", Map.of("ambito_punto", e.puntoId()), "ESM");
            default -> new Ambito("FALSE", Map.of(), "Sin alcance");
        };
    }

    boolean nacional() {
        return "TRUE".equals(condicion);
    }
}
