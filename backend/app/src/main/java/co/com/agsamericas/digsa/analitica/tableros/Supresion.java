package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Fila;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Kpi;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Tabla;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Supresión de celdas pequeñas (analitica-tableros, invariante «Toda salida general es agregada»; decisión de diseño: parámetro
 * {@code digsa.analitica.tableros.supresion-minima}, desactivado por defecto; lo decide la DIGSA). Con k &gt; 0, en cada fila y KPI cuyo
 * número de pacientes distintos esté entre 1 y k − 1 se ocultan los pacientes, los pacientes-mes y el promedio por paciente («&lt;k»): con
 * un ESM y un diagnóstico sensible, una celda de 1 paciente revelaría su gasto. Las sumas (gasto, unidades, dispensaciones) no se tocan:
 * siguen cuadrando con el insumo (14.5). Se aplica a pantalla, descargas, consulta estructurada y asistente (todos pasan por aquí) y se
 * declara en los metadatos.
 */
final class Supresion {

    /** Columnas que dependen de los pacientes de la fila. */
    static final Set<String> COLUMNAS = Set.of("pacientes", "pacientesMes", "promedioPaciente");
    /** KPI de pacientes y el promedio que depende de ellos. */
    static final Set<String> KPIS = Set.of("pacientes", "pacientesAltoCosto", "poliformulados");

    private final int k;

    Supresion(int k) {
        this.k = k;
    }

    private boolean oculta(Object pacientes) {
        if (k <= 0 || pacientes == null) {
            return false;
        }
        long n = pacientes instanceof Number x ? x.longValue() : -1;
        return n > 0 && n < k;
    }

    private String texto() {
        return "<" + k;
    }

    List<Kpi> kpis(List<Kpi> kpis) {
        if (k <= 0) {
            return kpis;
        }
        boolean pocos = kpis.stream().anyMatch(x -> x.clave().equals("pacientes") && oculta(x.valor()));
        List<Kpi> r = new ArrayList<>();
        for (Kpi x : kpis) {
            boolean ocultar = KPIS.contains(x.clave()) && oculta(x.valor()) || x.clave().equals("promedioPaciente") && pocos;
            r.add(ocultar ? new Kpi(x.clave(), x.etiqueta(), x.tipo(), null, texto(), null, null, null, null, null,
                    "Oculto: menos de " + k + " pacientes (supresión de celdas pequeñas)", x.rastreo()) : x);
        }
        return r;
    }

    List<Tabla> tablas(List<Tabla> tablas) {
        if (k <= 0) {
            return tablas;
        }
        List<Tabla> r = new ArrayList<>();
        for (Tabla t : tablas) {
            int iPac = -1;
            for (int i = 0; i < t.columnas().size(); i++) {
                if (t.columnas().get(i).clave().equals("pacientes")) {
                    iPac = i;
                }
            }
            if (iPac < 0) {
                r.add(t);
                continue;
            }
            List<Fila> filas = new ArrayList<>();
            for (Fila f : t.filas()) {
                if (!oculta(f.valores().get(iPac))) {
                    filas.add(f);
                    continue;
                }
                List<Object> v = new ArrayList<>(f.valores());
                List<String> tx = new ArrayList<>(f.textos());
                for (int i = 0; i < t.columnas().size(); i++) {
                    if (COLUMNAS.contains(t.columnas().get(i).clave())) {
                        v.set(i, null);
                        tx.set(i, texto());
                    }
                }
                filas.add(new Fila(f.clave(), f.rastreo(), java.util.Collections.unmodifiableList(v), List.copyOf(tx), f.tipo()));
            }
            r.add(new Tabla(t.id(), t.titulo(), t.grafico(), t.valorGrafico(), t.comparado(), t.columnas(), filas, t.nota()));
        }
        return r;
    }
}
