package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.JdbcReportes;
import co.com.agsamericas.digsa.reportes.Opcion;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import co.com.agsamericas.digsa.reportes.SqlPeriodo;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Consolidado de visitas por proceso (componente del checklist; Anexo 5, prueba 13). Cuenta solo las visitas cerradas: las
 * calificaciones de una visita en curso o reabierta son borradores (19.9). Cuenta las respuestas del checklist, como antes del motor
 * (19.9); el tablero y el consolidado por lugar leen el resumen del acta, que en los seguimientos guarda solo los hallazgos
 * verificados (duda R.13).
 */
@Component
class ReporteVisitasProceso implements ProveedorReporte {

    private static final DefinicionReporte DEFINICION = DefinicionReporte.nuevo("proceso", "Consolidado de visitas por proceso (componente)")
            .descripcion("Por componente del checklist: visitas cerradas, ítems calificados y % de cumplimiento.")
            .grupo(GrupoReporte.VISITAS).modulo("visitas").fuente(FiltrosVisitas.FUENTE).posicion(3)
            .periodo(CampoTemporal.FECHA, EnumSet.range(Agrupacion.DIA, Agrupacion.ANIO))
            .filtro(FiltrosVisitas.LUGAR).filtro(FiltrosVisitas.MUNICIPIO).filtro(FiltrosVisitas.FUERZA).filtro(FiltrosVisitas.UNIDAD)
            .filtro(FiltrosVisitas.REGIONAL).filtro(FiltrosVisitas.COMPONENTE)
            .columna("Componente", TipoColumna.TEXTO).columna("Proceso", TipoColumna.TEXTO).columna("Visitas", TipoColumna.ENTERO)
            .columna("Ítems calificados", TipoColumna.ENTERO).columna("Cumple", TipoColumna.ENTERO).columna("No cumple", TipoColumna.ENTERO)
            .columna("No aplica", TipoColumna.ENTERO).columna("% cumplimiento", TipoColumna.PORCENTAJE)
            .orden("periodo y código del componente").maxFilas(ReporteDetalleVisitas.MAX_FILAS).crear();

    private final JdbcClient jdbc;
    private final FiltrosVisitas filtros;

    ReporteVisitasProceso(JdbcReportes jdbc, FiltrosVisitas filtros) {
        this.jdbc = jdbc.jdbc();
        this.filtros = filtros;
    }

    @Override
    public DefinicionReporte definicion() {
        return DEFINICION;
    }

    @Override
    public List<Opcion> opciones(String filtro, Alcance alcance) {
        return filtros.opciones(filtro, alcance);
    }

    @Override
    public void filas(ConsultaReporte c, Consumer<List<Object>> destino) {
        FiltrosVisitas.Where w = filtros.where(c);
        String periodo = c.agrupacion() == null ? null : SqlPeriodo.etiqueta(c.agrupacion(), "v.fecha");
        String sql = "SELECT " + (periodo == null ? "" : periodo + " AS periodo, ") + """
                c.codigo, max(c.nombre) AS nombre, count(DISTINCT v.id) AS visitas,
                       count(*) FILTER (WHERE vr.calificacion IS NOT NULL) AS calificados, count(*) FILTER (WHERE vr.calificacion = 'C') AS c,
                       count(*) FILTER (WHERE vr.calificacion = 'NC') AS nc, count(*) FILTER (WHERE vr.calificacion = 'NA') AS na
                FROM visitas v JOIN puntos p ON p.id = v.punto_id JOIN visitas_respuestas vr ON vr.visita_id = v.id
                JOIN chk_items i ON i.id = vr.item_id JOIN chk_componentes c ON c.id = i.componente_id""" + w.sql()
                + (c.filtra("componente") ? " AND c.codigo IN (:f_componente)" : "")
                + " AND v.estado = 'cerrada' GROUP BY " + (periodo == null ? "" : "1, ") + "c.codigo ORDER BY " + (periodo == null ? "" : "1, ") + "c.codigo"
                + JdbcReportes.limite(c);
        var q = jdbc.sql(sql).params(w.params());
        if (c.filtra("componente")) {
            q = q.param("f_componente", c.valores("componente"));
        }
        q.query(rs -> {
            int cu = rs.getInt("c");
            int nc = rs.getInt("nc");
            List<Object> fila = new ArrayList<>(9);
            if (periodo != null) {
                fila.add(rs.getString("periodo"));
            }
            fila.add(rs.getString("codigo"));
            fila.add(rs.getString("nombre"));
            fila.add(rs.getInt("visitas"));
            fila.add(rs.getInt("calificados"));
            fila.add(cu);
            fila.add(nc);
            fila.add(rs.getInt("na"));
            fila.add(ReporteDetalleVisitas.porcentaje(cu, nc));
            destino.accept(fila);
        });
    }
}
