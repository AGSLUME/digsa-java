package co.com.agsamericas.digsa.reglas;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Propuesta de agenda (docs/especificaciones/propuesta-agenda; decisiones 13 y 32; app/Support/PropuestaAgenda.php). Lógica pura:
 * <ol>
 * <li>Qué se visita: los puntos del ciclo que aún no tienen visita (lo calcula quien llama).</li>
 * <li>Auditor líder: el asignado al punto si puede hacerla; si no, entre los que cumplen el requisito, el más cercano a su municipio
 * base (midiendo desde «se llega desde»), repartiendo la carga. En el nivel central se suma un ingeniero de sistemas.</li>
 * <li>Fecha: días hábiles posibles, máximo N visitas por auditor y día, y el mismo día solo municipios cercanos; recorrido desde el
 * municipio base, siempre al punto más cercano.</li>
 * <li>Borrador: lo fijado por el coordinador se respeta y ocupa su lugar.</li>
 * </ol>
 */
public final class PropuestaAgenda {

    /** Ciclo: unidad, modalidad (nula = cualquiera), el tipo que crea y los tipos que lo cubren (Anexo 3 p.12-13). */
    public record Ciclo(String codigo, String nombre, String unidad, String modalidad, String tipo, Set<String> cubren, boolean anual) {
    }

    /** Suministro, satélite y consignación no tienen ciclo en sitio (decisión 2026-10-20 16.1; Anexo 3 p.15). */
    public static final Map<String, Ciclo> CICLOS = ciclos();

    private static Map<String, Ciclo> ciclos() {
        Map<String, Ciclo> m = new LinkedHashMap<>();
        m.put("linea_base", new Ciclo("linea_base", "Línea base: visita inicial a los ESM de dispensación", "ESM", "Dispensación", "inicial", Set.of("inicial"), false));
        m.put("anual", new Ciclo("anual", "Ciclo anual de los ESM de dispensación", "ESM", "Dispensación", "anual", Set.of("inicial", "anual"), true));
        m.put("farmacia", new Ciclo("farmacia", "Farmacias alternas (anual)", "FA", null, "farmacia", Set.of("farmacia"), true));
        m.put("central", new Ciclo("central", "Nivel central del OPL (anual)", "NC", null, "central", Set.of("central"), true));
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Roles que lideran una visita asistencial; el ingeniero de sistemas y «otro» acompañan (duda 13.2). */
    public static final Set<String> ROLES_LIDER = Set.of("quimico_farmaceutico", "regente", "auditor_salud");
    /** Cuántos km «vale» cada visita de más frente al auditor menos cargado (reparto de la carga). */
    public static final double KM_POR_VISITA = 60.0;
    /** Distancia de quien no tiene ubicación: queda al final. */
    static final double SIN_UBICACION = 5000.0;

    private PropuestaAgenda() {
    }

    public record Punto(long id, String nombre, String unidad, String municipio, String llegaDesde, Long auditorAsignado, boolean exigeQf) {
        /** Municipio de acceso: «se llega desde» o el del punto. */
        String lugar() {
            return llegaDesde != null && !llegaDesde.isBlank() ? llegaDesde : municipio;
        }
    }

    public record Auditor(long id, String nombre, String rol, String municipioBase, int carga) {
    }

    /** Lo que el coordinador fijó en una fila. */
    public record Fijo(Long auditorId, List<Long> otros, LocalDate fecha) {
    }

    public record Item(long puntoId, Long auditorId, List<Long> otros, LocalDate fecha, Long km, boolean fijo, String motivo, List<String> alertas) {
    }

    public record Resumen(int visitas, int auditores, int dias, LocalDate desde, LocalDate hasta, boolean cabe) {
    }

    public record Resultado(List<Item> items, Resumen resumen, List<String> alertas) {
    }

    /** Distancia en km entre dos puntos {latitud, longitud} (haversine, radio 6371 km). */
    public static double distanciaKm(double[] a, double[] b) {
        double la1 = Math.toRadians(a[0]);
        double lo1 = Math.toRadians(a[1]);
        double la2 = Math.toRadians(b[0]);
        double lo2 = Math.toRadians(b[1]);
        double h = Math.pow(Math.sin((la2 - la1) / 2), 2) + Math.cos(la1) * Math.cos(la2) * Math.pow(Math.sin((lo2 - lo1) / 2), 2);
        return 2 * 6371.0 * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** ¿El auditor puede liderar la visita a ese punto? Donde se exige, solo el químico farmacéutico (Anexo 3 p.9). */
    public static boolean puedeLiderar(Punto p, Auditor a) {
        return p.exigeQf() ? "quimico_farmaceutico".equals(a.rol()) : ROLES_LIDER.contains(a.rol());
    }

    /**
     * Arma la propuesta.
     * @param ocupados auditor → día → municipios de acceso de las visitas que ya tiene ese día
     * @param fijos    punto → lo que fijó el coordinador
     */
    public static Resultado generar(List<Punto> puntos, List<Auditor> auditores, Map<String, double[]> coords, List<LocalDate> dias, int porDia, double kmMismoDia,
                                    Map<Long, Map<LocalDate, List<String>>> ocupadosEntrada, Map<Long, Fijo> fijos) {
        Map<Long, Auditor> aud = new LinkedHashMap<>();
        auditores.forEach(a -> aud.put(a.id(), a));
        Map<Long, Integer> carga = new HashMap<>();
        auditores.forEach(a -> carga.put(a.id(), a.carga()));
        Map<Long, Map<LocalDate, List<String>>> ocupados = new HashMap<>();
        ocupadosEntrada.forEach((k, v) -> {
            Map<LocalDate, List<String>> copia = new HashMap<>();
            v.forEach((d, l) -> copia.put(d, new ArrayList<>(l)));
            ocupados.put(k, copia);
        });

        // Lo fijado ocupa su lugar primero
        Map<Long, Item> items = new LinkedHashMap<>();
        for (Punto p : puntos) {
            Fijo f = fijos.get(p.id());
            if (f == null) {
                continue;
            }
            List<Long> otros = f.otros() == null ? List.of() : List.copyOf(f.otros());
            items.put(p.id(), new Item(p.id(), f.auditorId(), otros, f.fecha(), null, true, "Ajustado por el coordinador", List.of()));
            List<Long> equipo = new ArrayList<>();
            if (f.auditorId() != null) {
                equipo.add(f.auditorId());
            }
            equipo.addAll(otros);
            for (long a : equipo) {
                carga.merge(a, 1, Integer::sum);
                if (f.fecha() != null) {
                    ocupados.computeIfAbsent(a, k -> new HashMap<>()).computeIfAbsent(f.fecha(), k -> new ArrayList<>()).add(p.lugar());
                }
            }
        }

        // Paso 2: primero los puntos con menos auditores posibles (p. ej. los que exigen químico farmacéutico)
        List<Punto> libres = new ArrayList<>(puntos.stream().filter(p -> !fijos.containsKey(p.id())).toList());
        Map<Long, List<Auditor>> posibles = new HashMap<>();
        libres.forEach(p -> posibles.put(p.id(), auditores.stream().filter(a -> puedeLiderar(p, a)).toList()));
        libres.sort(Comparator.<Punto>comparingInt(p -> posibles.get(p.id()).size()).thenComparingLong(Punto::id));

        Map<Long, List<Punto>> porAuditor = new LinkedHashMap<>();
        Map<Long, String> motivos = new HashMap<>();
        Map<Long, List<String>> alertas = new HashMap<>();
        Map<Long, Long> lider = new HashMap<>();
        Map<Long, Long> kms = new HashMap<>();
        Map<Long, List<Long>> otrosDe = new HashMap<>();
        for (Punto p : libres) {
            List<String> al = new ArrayList<>();
            alertas.put(p.id(), al);
            otrosDe.put(p.id(), new ArrayList<>());
            double[] o = ubicacion(coords, p.lugar());
            if (o == null) {
                o = ubicacion(coords, p.municipio());
            }
            if (o == null) {
                al.add("El municipio del punto no tiene coordenadas: no se mide la cercanía.");
            }
            Auditor asignado = p.auditorAsignado() == null ? null : aud.get(p.auditorAsignado());
            Auditor elegido;
            if (asignado != null && puedeLiderar(p, asignado)) {
                elegido = asignado;
                motivos.put(p.id(), "Auditor asignado al punto");
            } else {
                if (asignado != null) {
                    al.add("El auditor asignado (" + asignado.nombre() + ") no cumple el requisito del punto.");
                }
                List<Auditor> cands = new ArrayList<>(posibles.get(p.id()));
                if (cands.isEmpty()) {
                    al.add(p.exigeQf() ? "No hay químico farmacéutico activo para este punto." : "No hay auditor asistencial activo para este punto.");
                    continue;
                }
                int min = cands.stream().mapToInt(a -> carga.getOrDefault(a.id(), 0)).min().orElse(0);
                double[] origen = o;
                Comparator<Auditor> porPuntaje = Comparator.comparingDouble((Auditor a) -> {
                    double[] b = ubicacion(coords, a.municipioBase());
                    double km = origen != null && b != null ? distanciaKm(b, origen) : SIN_UBICACION;
                    return km + KM_POR_VISITA * (carga.getOrDefault(a.id(), 0) - min);
                }).thenComparingLong(Auditor::id);
                cands.sort(porPuntaje);
                elegido = cands.get(0);
                motivos.put(p.id(), "El más cercano con el requisito, repartiendo la carga");
            }
            double[] b = ubicacion(coords, elegido.municipioBase());
            if (b == null) {
                al.add(elegido.nombre() + " no tiene municipio base con coordenadas.");
            }
            lider.put(p.id(), elegido.id());
            if (o != null && b != null) {
                kms.put(p.id(), Math.round(distanciaKm(b, o)));
            }
            carga.merge(elegido.id(), 1, Integer::sum);
            // Nivel central: se suma el ingeniero de sistemas menos cargado (ítems de sistemas)
            if ("NC".equals(p.unidad())) {
                Auditor ing = auditores.stream().filter(a -> "ingeniero_sistemas".equals(a.rol()))
                        .min(Comparator.<Auditor>comparingInt(a -> carga.getOrDefault(a.id(), 0)).thenComparingLong(Auditor::id)).orElse(null);
                if (ing != null) {
                    otrosDe.get(p.id()).add(ing.id());
                    carga.merge(ing.id(), 1, Integer::sum);
                } else {
                    al.add("No hay ingeniero de sistemas activo para los ítems de sistemas del nivel central.");
                }
            }
            porAuditor.computeIfAbsent(elegido.id(), k -> new ArrayList<>()).add(p);
        }

        // Paso 3: por auditor, recorrido desde su municipio base (siempre al punto más cercano) y el primer día que cabe
        Map<Long, LocalDate> fecha = new HashMap<>();
        for (Map.Entry<Long, List<Punto>> e : porAuditor.entrySet()) {
            long aId = e.getKey();
            List<Punto> lista = new ArrayList<>(e.getValue());
            double[] actual = ubicacion(coords, aud.get(aId).municipioBase());
            int cursor = 0;
            while (!lista.isEmpty()) {
                int k = 0;
                if (actual != null) {
                    double mejor = Double.POSITIVE_INFINITY;
                    for (int i = 0; i < lista.size(); i++) {
                        double[] o = origen(coords, lista.get(i));
                        double d = o != null ? distanciaKm(actual, o) : Double.POSITIVE_INFINITY;
                        if (d < mejor) {
                            mejor = d;
                            k = i;
                        }
                    }
                }
                Punto p = lista.remove(k);
                List<Long> equipo = new ArrayList<>();
                equipo.add(aId);
                equipo.addAll(otrosDe.get(p.id()));
                int i = cursor;
                while (i < dias.size() && !cabe(equipo, dias.get(i), p.lugar(), ocupados, coords, porDia, kmMismoDia)) {
                    i++;
                }
                if (i >= dias.size()) {
                    alertas.get(p.id()).add("No cabe antes del límite del ciclo: amplíe el plazo o asígnela a mano.");
                    continue;
                }
                LocalDate dia = dias.get(i);
                fecha.put(p.id(), dia);
                for (long a : equipo) {
                    ocupados.computeIfAbsent(a, x -> new HashMap<>()).computeIfAbsent(dia, x -> new ArrayList<>()).add(p.lugar());
                }
                cursor = i;
                double[] o = origen(coords, p);
                actual = o != null ? o : actual;
            }
        }

        for (Punto p : libres) {
            items.put(p.id(), new Item(p.id(), lider.get(p.id()), List.copyOf(otrosDe.get(p.id())), fecha.get(p.id()), kms.get(p.id()), false,
                    motivos.get(p.id()), List.copyOf(alertas.get(p.id()))));
        }
        // En el orden de los puntos recibidos
        List<Item> lista = new ArrayList<>();
        for (Punto p : puntos) {
            if (items.containsKey(p.id())) {
                lista.add(items.get(p.id()));
            }
        }
        Set<LocalDate> fechas = new LinkedHashSet<>();
        lista.stream().map(Item::fecha).filter(java.util.Objects::nonNull).forEach(fechas::add);
        long sinAuditor = lista.stream().filter(x -> x.auditorId() == null).count();
        long sinFecha = lista.stream().filter(x -> x.auditorId() != null && x.fecha() == null).count();
        List<String> generales = new ArrayList<>();
        if (sinAuditor > 0) {
            generales.add(sinAuditor + " punto(s) sin auditor posible.");
        }
        if (sinFecha > 0) {
            generales.add(sinFecha + " visita(s) no caben antes del límite.");
        }
        if (dias.isEmpty()) {
            generales.add("No hay días hábiles entre la primera fecha posible y el límite del ciclo.");
        }
        int nAuditores = (int) lista.stream().map(Item::auditorId).filter(java.util.Objects::nonNull).distinct().count();
        Resumen r = new Resumen(lista.size(), nAuditores, fechas.size(), fechas.stream().min(LocalDate::compareTo).orElse(null),
                fechas.stream().max(LocalDate::compareTo).orElse(null), sinAuditor == 0 && sinFecha == 0);
        return new Resultado(lista, r, generales);
    }

    private static double[] ubicacion(Map<String, double[]> coords, String municipio) {
        return municipio == null ? null : coords.get(municipio);
    }

    private static double[] origen(Map<String, double[]> coords, Punto p) {
        double[] o = ubicacion(coords, p.lugar());
        return o != null ? o : ubicacion(coords, p.municipio());
    }

    /** ¿Cabe una visita ese día para todo el equipo? Máximo por día y, el mismo día, solo municipios cercanos (sin ubicación: no se juntan). */
    static boolean cabe(Collection<Long> equipo, LocalDate dia, String lugar, Map<Long, Map<LocalDate, List<String>>> ocupados, Map<String, double[]> coords,
                        int porDia, double kmMismoDia) {
        for (long a : equipo) {
            List<String> ya = ocupados.getOrDefault(a, Map.of()).getOrDefault(dia, List.of());
            if (ya.size() >= porDia) {
                return false;
            }
            for (String m : ya) {
                if (java.util.Objects.equals(m, lugar)) {
                    continue;
                }
                if (m == null || lugar == null || !coords.containsKey(m) || !coords.containsKey(lugar)) {
                    return false;
                }
                if (distanciaKm(coords.get(m), coords.get(lugar)) > kmMismoDia) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Días hábiles posibles hasta el límite: el día D respeta la notificación previa si su límite de notificación (el hábil anterior a
     * D − 15 días, {@link Plazos#limiteNotificacion}) no es anterior a hoy. Es la misma regla de las alertas de la agenda.
     */
    public static List<LocalDate> dias(Plazos plazos, LocalDate hoy, LocalDate desde, LocalDate hasta) {
        List<LocalDate> r = new ArrayList<>();
        LocalDate d = desde.isAfter(hoy) ? desde : hoy.plusDays(1);
        for (; !d.isAfter(hasta); d = d.plusDays(1)) {
            if (plazos.esHabil(d) && !plazos.limiteNotificacion("opl", d).isBefore(hoy)) {
                r.add(d);
            }
        }
        return r;
    }

    /** Periodo del año de contrato N (decisión 2026-10-20 13.4): de inicio + (N−1) años al día anterior a inicio + N años. */
    public static LocalDate[] anioDeContrato(LocalDate inicio, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("El año de contrato empieza en 1");
        }
        return new LocalDate[] {inicio.plusYears(n - 1L), inicio.plusYears(n).minusDays(1)};
    }

    /** Año de contrato en que cae la fecha (1 si es anterior al inicio). */
    public static int anioDe(LocalDate inicio, LocalDate fecha) {
        int n = 1;
        while (!fecha.isBefore(inicio.plusYears(n))) {
            n++;
        }
        return n;
    }
}
