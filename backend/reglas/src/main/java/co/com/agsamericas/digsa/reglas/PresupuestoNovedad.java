package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Novedades presupuestales (Anexo 5 §4.6: «adiciones, disminuciones, reajustes, rebalanceos, recomposición presupuestal mediante
 * formulario(s)»; caso M7-2). Convierte lo que se diligencia en el formulario en el cambio con signo de cada línea y valida sus reglas.
 * Nada se sobrescribe: cada novedad es un movimiento nuevo con el antes y el después de cada línea.
 */
public final class PresupuestoNovedad {

    public enum Tipo {
        ASIGNACION_INICIAL("asignacion_inicial", "Asignación inicial"),
        ADICION("adicion", "Adición"),
        DISMINUCION("disminucion", "Disminución"),
        /** Ajuste con signo (o un % sobre un ámbito), sin neto cero (duda P.9). */
        REAJUSTE("reajuste", "Reajuste"),
        /** Traslado de una línea a otra de la misma vigencia: neto cero. */
        REBALANCEO("rebalanceo", "Rebalanceo"),
        /** Redistribución de varias líneas con valores con signo: suma exactamente 0,00. */
        RECOMPOSICION("recomposicion", "Recomposición");

        private final String codigo;
        private final String nombre;

        Tipo(String codigo, String nombre) {
            this.codigo = codigo;
            this.nombre = nombre;
        }

        public String codigo() {
            return codigo;
        }

        public String nombre() {
            return nombre;
        }

        public static Optional<Tipo> deCodigo(String c) {
            return Arrays.stream(values()).filter(t -> t.codigo.equals(c)).findFirst();
        }
    }

    /** Lo que llega del formulario por línea: el valor tal como se escribió (positivo, o con signo en reajuste y recomposición). */
    public record Item(long lineaId, BigDecimal valor) {
    }

    /** Cambio con signo de cada línea, en el orden de entrada, o el motivo del rechazo. */
    public record Resultado(Map<Long, BigDecimal> cambios, String problema) {
        public boolean valido() {
            return problema == null;
        }

        static Resultado error(String p) {
            return new Resultado(Map.of(), p);
        }

        public BigDecimal neto() {
            return cambios.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        }
    }

    public static final int SOPORTE_MIN = 5;
    public static final int SOPORTE_MAX = 300;
    public static final int MAX_LINEAS = 5000;

    private PresupuestoNovedad() {
    }

    /** El soporte de la DIGSA (oficio, acto) es obligatorio: de 5 a 300 caracteres. */
    public static Optional<String> problemaSoporte(String soporte) {
        String s = soporte == null ? "" : soporte.strip();
        if (s.isEmpty()) {
            return Optional.of("El soporte de la DIGSA es obligatorio.");
        }
        if (s.length() < SOPORTE_MIN || s.length() > SOPORTE_MAX) {
            return Optional.of("El soporte de la DIGSA debe tener entre " + SOPORTE_MIN + " y " + SOPORTE_MAX + " caracteres.");
        }
        return Optional.empty();
    }

    /**
     * Convierte los ítems en cambios con signo según el tipo. En el rebalanceo, el primer ítem es el origen y el segundo el destino, y
     * ambos llevan el mismo valor positivo.
     */
    public static Resultado cambios(Tipo tipo, List<Item> items) {
        if (items == null || items.isEmpty()) {
            return Resultado.error("La novedad no tiene líneas.");
        }
        if (items.size() > MAX_LINEAS) {
            return Resultado.error("La novedad tiene más de " + MAX_LINEAS + " líneas.");
        }
        Set<Long> vistas = new HashSet<>();
        for (Item i : items) {
            if (i.valor() == null) {
                return Resultado.error("Falta el valor de una línea.");
            }
            if (i.valor().scale() > 2 && i.valor().stripTrailingZeros().scale() > 2) {
                return Resultado.error("Los valores tienen máximo 2 decimales.");
            }
            if (i.valor().abs().compareTo(Presupuesto.MAXIMO) > 0) {
                return Resultado.error("El valor " + Presupuesto.formato(i.valor()) + " pasa el máximo permitido.");
            }
            if (!vistas.add(i.lineaId())) {
                return Resultado.error(tipo == Tipo.REBALANCEO ? "El origen y el destino del rebalanceo deben ser líneas distintas."
                        : "Una línea aparece dos veces en la novedad.");
            }
        }
        Map<Long, BigDecimal> c = new LinkedHashMap<>();
        switch (tipo) {
            case ASIGNACION_INICIAL, ADICION, DISMINUCION -> {
                for (Item i : items) {
                    if (i.valor().signum() <= 0) {
                        return Resultado.error("En una " + tipo.nombre().toLowerCase(java.util.Locale.ROOT) + " cada valor debe ser mayor que 0.");
                    }
                    c.put(i.lineaId(), escala(tipo == Tipo.DISMINUCION ? i.valor().negate() : i.valor()));
                }
            }
            case REAJUSTE -> {
                for (Item i : items) {
                    if (i.valor().signum() == 0) {
                        return Resultado.error("En un reajuste cada valor debe ser distinto de 0.");
                    }
                    c.put(i.lineaId(), escala(i.valor()));
                }
            }
            case REBALANCEO -> {
                if (items.size() != 2) {
                    return Resultado.error("El rebalanceo va de una línea de origen a una de destino.");
                }
                BigDecimal v = items.get(0).valor();
                if (v.signum() <= 0 || items.get(1).valor().compareTo(v) != 0) {
                    return Resultado.error("El valor del rebalanceo debe ser mayor que 0 e igual en el origen y el destino.");
                }
                c.put(items.get(0).lineaId(), escala(v.negate()));
                c.put(items.get(1).lineaId(), escala(v));
            }
            case RECOMPOSICION -> {
                if (items.size() < 2) {
                    return Resultado.error("La recomposición redistribuye al menos dos líneas.");
                }
                for (Item i : items) {
                    if (i.valor().signum() == 0) {
                        return Resultado.error("En una recomposición cada valor debe ser distinto de 0.");
                    }
                    c.put(i.lineaId(), escala(i.valor()));
                }
                BigDecimal suma = c.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                if (suma.signum() != 0) {
                    return Resultado.error("La recomposición debe sumar 0,00 (suma " + Presupuesto.formato(suma) + ").");
                }
            }
        }
        return new Resultado(c, null);
    }

    /**
     * Reajuste por porcentaje sobre un ámbito (una fuerza o el país): cada línea cambia su asignado × % ÷ 100 (HALF_EVEN, 2 decimales) y
     * el residuo del redondeo (hasta medio centavo por línea) va a la línea de mayor asignado; con empate, a la de menor id, para que el
     * resultado no dependa del orden. Las líneas con cambio 0 se omiten.
     */
    public static Resultado reajustePorcentaje(Map<Long, BigDecimal> asignados, BigDecimal porcentaje) {
        if (porcentaje == null || porcentaje.signum() == 0) {
            return Resultado.error("El porcentaje del reajuste debe ser distinto de 0.");
        }
        if (porcentaje.compareTo(BigDecimal.valueOf(-100)) < 0 || porcentaje.compareTo(BigDecimal.valueOf(1000)) > 0) {
            return Resultado.error("El porcentaje del reajuste debe estar entre -100 y 1000.");
        }
        if (asignados.isEmpty()) {
            return Resultado.error("El ámbito del reajuste no tiene líneas con presupuesto.");
        }
        BigDecimal cien = BigDecimal.valueOf(100);
        BigDecimal total = asignados.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal objetivo = total.multiply(porcentaje).divide(cien, 2, RoundingMode.HALF_EVEN);
        Map<Long, BigDecimal> c = new LinkedHashMap<>();
        long mayor = -1;
        BigDecimal mayorValor = null;
        for (Map.Entry<Long, BigDecimal> e : asignados.entrySet()) {
            c.put(e.getKey(), e.getValue().multiply(porcentaje).divide(cien, 2, RoundingMode.HALF_EVEN));
            int cmp = mayorValor == null ? 1 : e.getValue().compareTo(mayorValor);
            if (cmp > 0 || (cmp == 0 && e.getKey() < mayor)) {
                mayor = e.getKey();
                mayorValor = e.getValue();
            }
        }
        BigDecimal suma = c.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        c.merge(mayor, objetivo.subtract(suma), BigDecimal::add);
        c.values().removeIf(v -> v.signum() == 0);
        if (c.isEmpty()) {
            return Resultado.error("El reajuste no cambia ninguna línea (valores muy pequeños).");
        }
        return new Resultado(c, null);
    }

    /** Estado de una línea antes de aplicar la novedad. */
    public record Saldo(String nombre, BigDecimal asignado, BigDecimal facturado) {
    }

    /**
     * Una novedad no puede dejar el asignado negativo ni por debajo de lo ejecutado (facturado) (duda P.10). La adición nunca se bloquea.
     * Una línea que resta sin saldo conocido se rechaza (falla cerrado).
     *
     * @return los mensajes, uno por línea que no cumple; vacío si todas cumplen
     */
    public static List<String> problemasDeSaldo(Map<Long, BigDecimal> cambios, Map<Long, Saldo> saldos) {
        List<String> r = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : cambios.entrySet()) {
            Saldo s = saldos.get(e.getKey());
            if (e.getValue().signum() >= 0) {
                continue;
            }
            if (s == null) {
                r.add("La línea " + e.getKey() + " no tiene saldo: no se le puede restar.");
                continue;
            }
            BigDecimal despues = s.asignado().add(e.getValue());
            if (despues.signum() < 0) {
                r.add(s.nombre() + " quedaría con " + Presupuesto.formato(despues) + ": el asignado no puede ser negativo.");
            } else if (despues.compareTo(s.facturado()) < 0) {
                r.add(s.nombre() + " quedaría con " + Presupuesto.formato(despues) + ", menos que lo ejecutado (" + Presupuesto.formato(s.facturado()) + ").");
            }
        }
        return r;
    }

    private static BigDecimal escala(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }
}
