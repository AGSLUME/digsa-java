package co.com.agsamericas.digsa.reglas;

import java.util.Arrays;
import java.util.Optional;

/**
 * Perfiles de acceso (decisiones de la séptima ronda, 2026-10-15) más Analista, que la DIGSA agrega al Anexo 5 para la prueba en
 * sitio (respuesta AGS-30 del 19-oct-2026: Analista, Auditor, Administrador y OPL). El id es fijo: es el de la tabla de perfiles.
 * Consulta (4) se retiró (decisión 20) y queda inactivo.
 */
public enum Perfil {
    ADMINISTRADOR(1, "Administrador"),
    COORDINADOR(2, "Coordinador"),
    AUDITOR(3, "Auditor"),
    CONSULTA(4, "Consulta"),
    DIGSA(5, "DIGSA"),
    SANIDAD(6, "Sanidad"),
    ESM(7, "ESM"),
    OPL_NACIONAL(8, "OPL nacional"),
    OPL_REGIONAL(9, "OPL regional"),
    ANALISTA(10, "Analista");

    private final int id;
    private final String nombre;

    Perfil(int id, String nombre) {
        this.id = id;
        this.nombre = nombre;
    }

    public int id() {
        return id;
    }

    public String nombre() {
        return nombre;
    }

    /** Por el nombre exacto (otra grafía no cuenta: "administrador" no es Administrador). */
    public static Optional<Perfil> deNombre(String nombre) {
        return Arrays.stream(values()).filter(p -> p.nombre.equals(nombre)).findFirst();
    }

    public static Optional<Perfil> deId(int id) {
        return Arrays.stream(values()).filter(p -> p.id == id).findFirst();
    }

    public boolean esOpl() {
        return this == OPL_NACIONAL || this == OPL_REGIONAL;
    }
}
