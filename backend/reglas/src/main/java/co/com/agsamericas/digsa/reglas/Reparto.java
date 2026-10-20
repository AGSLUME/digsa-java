package co.com.agsamericas.digsa.reglas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Equipo de la visita y reparto del formulario (decisiones 2, 2a, 2b, 2c y 15; Anexo 5, prueba 11: asignar componentes de una misma
 * visita a distintos auditores, y cada uno ve solo lo suyo). Equivalente de app/Support/Reparto.php.
 *
 * <p>Partes del formulario: cada componente (C01…), OBL (obligaciones de la minuta) y SIS (ítems de sistemas, solo si en el equipo hay
 * un ingeniero). En la reescritura el coordinador elige los componentes al crear la visita (respuesta VCO P29); el reparto automático
 * es la sugerencia, y el coordinador puede moverlo respetando el rol.
 */
public final class Reparto {

    /** Rol técnico del auditor (decisión 2a; Anexo 1). */
    public static final Map<String, String> ROLES;
    /** Ítems de sistemas: van al ingeniero si lo hay en la visita (decisión 15; SO-10 queda para el equipo asistencial). */
    public static final List<String> SISTEMAS = List.of("SO-01", "NC-04", "NC-05", "P04-16", "I05-16");
    /** Otros auditores además del líder (decisión 2). */
    public static final int MAX_OTROS = 5;
    /** Peso de las obligaciones de la minuta en el reparto equitativo (no se sabe cuántas tendrá). */
    public static final int PESO_OBLIGACIONES = 10;
    public static final String OBLIGACIONES = "OBL";
    public static final String PARTE_SISTEMAS = "SIS";

    static {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("quimico_farmaceutico", "Químico farmacéutico");
        r.put("regente", "Regente de farmacia");
        r.put("auditor_salud", "Auditor en salud");
        r.put("ingeniero_sistemas", "Ingeniero de sistemas");
        r.put("otro", "Otro");
        ROLES = java.util.Collections.unmodifiableMap(r);
    }

    /** Componente del catálogo con las unidades a las que aplica y los códigos de sus ítems. */
    public record Componente(String codigo, Set<String> unidades, List<String> items) {
    }

    /** Integrante del equipo, en el orden en que se registró (el orden desempata). */
    public record Integrante(long id, String rol, boolean lider) {
    }

    private Reparto() {
    }

    public static boolean esAsistencial(String rol) {
        return !"ingeniero_sistemas".equals(rol);
    }

    /**
     * Partes del formulario y su peso (número de ítems) para los componentes elegidos de la unidad.
     *
     * @return parte → peso, en el orden de los componentes; OBL y SIS al final
     */
    public static Map<String, Integer> partes(List<Componente> componentes, String unidad, boolean hayIngeniero) {
        Map<String, Integer> partes = new LinkedHashMap<>();
        int sis = 0;
        for (Componente c : componentes) {
            if (!c.unidades().contains(unidad)) {
                continue;
            }
            int n = 0;
            for (String i : c.items()) {
                if (hayIngeniero && SISTEMAS.contains(i)) {
                    sis++;
                } else {
                    n++;
                }
            }
            if (n > 0) {
                partes.put(c.codigo(), n);
            }
        }
        partes.put(OBLIGACIONES, PESO_OBLIGACIONES);
        if (sis > 0) {
            partes.put(PARTE_SISTEMAS, sis);
        }
        return partes;
    }

    /**
     * Reparto automático: SIS al ingeniero; lo demás, equitativo entre el equipo asistencial (o entre todos si no hay). Orden: peso
     * descendente y, a igual peso, código ascendente; cada parte al menos cargado y, a igual carga, al primero del equipo.
     *
     * @return parte → id del auditor
     */
    public static Map<String, Long> repartir(Map<String, Integer> partes, List<Integrante> equipo) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (equipo.isEmpty()) {
            return out;
        }
        Map<Long, Integer> carga = new LinkedHashMap<>();
        equipo.forEach(m -> carga.put(m.id(), 0));
        List<Integrante> ingenieros = equipo.stream().filter(m -> !esAsistencial(m.rol())).toList();
        List<Integrante> asistenciales = equipo.stream().filter(m -> esAsistencial(m.rol())).toList();
        List<Integrante> resto = asistenciales.isEmpty() ? equipo : asistenciales;
        List<String> orden = new ArrayList<>(partes.keySet());
        orden.sort(Comparator.<String>comparingInt(partes::get).reversed().thenComparing(Comparator.naturalOrder()));
        for (String parte : orden) {
            List<Integrante> candidatos = PARTE_SISTEMAS.equals(parte) && !ingenieros.isEmpty() ? ingenieros : resto;
            Integrante elegido = candidatos.getFirst();
            for (Integrante m : candidatos) {
                if (carga.get(m.id()) < carga.get(elegido.id())) {
                    elegido = m;
                }
            }
            out.put(parte, elegido.id());
            carga.merge(elegido.id(), partes.get(parte), Integer::sum);
        }
        return out;
    }

    /** ¿Se puede asignar esta parte a un auditor con este rol? (decisión 2c: "respetando el rol"). */
    public static boolean puedeRecibir(String parte, String rol, List<Integrante> equipo) {
        if (PARTE_SISTEMAS.equals(parte)) {
            return "ingeniero_sistemas".equals(rol);
        }
        boolean hayAsistencial = equipo.stream().anyMatch(m -> esAsistencial(m.rol()));
        return !hayAsistencial || esAsistencial(rol);
    }

    /** Parte del formulario de un ítem: SIS si es de sistemas y hay ingeniero; si no, su componente. */
    public static String parteDeItem(String codigoItem, String componente, boolean sisSeparado) {
        return sisSeparado && SISTEMAS.contains(codigoItem) ? PARTE_SISTEMAS : componente;
    }
}
