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
 * Consolidado de visitas por lugar (Anexo 5, prueba 13: lugar, componente auditado y número de visitas). Una fila por lugar y
 * componente asignado a la visita (decisión de la sesión 1, 2026-10-22), más la fila de las obligaciones del contrato (OBL) de las
 * visitas cerradas y la de las visitas sin componentes: así la suma de Cumple y No cumple de un lugar cuadra con el detalle. Cumple y
 * No cumple salen del resumen por componente que se guarda al enviar el acta (solo visitas cerradas, como el tablero); cada No cumple
 * es un hallazgo del componente.
 */
@Component
class ReporteVisitasLugar implements ProveedorReporte {

    static final String OBLIGACIONES = "OBL";
    static final String SIN_COMPONENTE = "Sin componente";

    private static final DefinicionReporte DEFINICION = DefinicionReporte.nuevo("lugar", "Consolidado de visitas por lugar")
            .descripcion("Por lugar y componente auditado: visitas por estado y resultados de las visitas cerradas.")
            .grupo(GrupoReporte.VISITAS).modulo("visitas").fuente(FiltrosVisitas.FUENTE).posicion(2)
            .periodo(CampoTemporal.FECHA, EnumSet.range(Agrupacion.DIA, Agrupacion.ANIO))
            .filtro(FiltrosVisitas.LUGAR).filtro(FiltrosVisitas.MUNICIPIO).filtro(FiltrosVisitas.FUERZA).filtro(FiltrosVisitas.UNIDAD)
            .filtro(FiltrosVisitas.REGIONAL).filtro(FiltrosVisitas.ESTADO).filtro(FiltrosVisitas.COMPONENTE)
            .columna("Lugar", TipoColumna.TEXTO).columna("Sigla", TipoColumna.TEXTO).columna("Municipio", TipoColumna.TEXTO)
            .columna("Fuerza", TipoColumna.TEXTO).columna("Unidad", TipoColumna.TEXTO).columna("Componente", TipoColumna.TEXTO)
            .columna("Visitas", TipoColumna.ENTERO).columna("Cerradas", TipoColumna.ENTERO).columna("En ejecución", TipoColumna.ENTERO)
            .columna("Por ejecutar", TipoColumna.ENTERO).columna("Canceladas", TipoColumna.ENTERO).columna("Cumple", TipoColumna.ENTERO)
            .columna("No cumple", TipoColumna.ENTERO).columna("% cumplimiento", TipoColumna.PORCENTAJE).columna("Hallazgos", TipoColumna.ENTERO)
            .orden("periodo, nombre e id del lugar y código del componente (OBL y sin componente al final)")
            .crear();

    private final JdbcClient jdbc;
    private final FiltrosVisitas filtros;

    ReporteVisitasLugar(JdbcReportes jdbc, FiltrosVisitas filtros) {
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
                p.id, p.nombre AS lugar, p.sigla, m.nombre AS municipio, p.fuerza, p.unidad, k.clave,
                       max(cc.nombre) AS componente_nombre, count(*) AS visitas,
                       count(*) FILTER (WHERE v.estado = 'cerrada') AS cerradas, count(*) FILTER (WHERE v.estado = 'ejecucion') AS ejecucion,
                       count(*) FILTER (WHERE v.estado IN ('programada', 'notificada', 'reprogramada')) AS por_ejecutar,
                       count(*) FILTER (WHERE v.estado = 'cancelada') AS canceladas,
                       coalesce(sum((rs.por_componente -> k.clave ->> 'c')::int) FILTER (WHERE v.estado = 'cerrada'), 0) AS c,
                       coalesce(sum((rs.por_componente -> k.clave ->> 'nc')::int) FILTER (WHERE v.estado = 'cerrada'), 0) AS nc
                FROM visitas v JOIN puntos p ON p.id = v.punto_id
                LEFT JOIN par_municipios m ON m.codigo = p.municipio
                LEFT JOIN visitas_resumen rs ON rs.visita_id = v.id AND v.estado = 'cerrada'
                CROSS JOIN LATERAL (SELECT vc.componente AS clave FROM visitas_componentes vc WHERE vc.visita_id = v.id
                                    UNION ALL SELECT 'OBL' WHERE rs.por_componente -> 'OBL' IS NOT NULL
                                    UNION ALL SELECT NULL WHERE NOT EXISTS (SELECT 1 FROM visitas_componentes x WHERE x.visita_id = v.id)) k
                LEFT JOIN chk_componentes cc ON cc.codigo = k.clave
                     AND cc.version = coalesce(v.checklist_version, (SELECT cv.version FROM chk_versiones cv WHERE cv.vigente LIMIT 1))""" + w.sql()
                + (c.filtra("componente") ? " AND k.clave IN (:f_componente)" : "")
                + " GROUP BY " + (periodo == null ? "" : "1, ") + "p.id, p.nombre, p.sigla, m.nombre, p.fuerza, p.unidad, k.clave"
                + " ORDER BY " + (periodo == null ? "" : "1, ") + "p.nombre, p.id, k.clave NULLS LAST" + JdbcReportes.limite(c);
        var q = jdbc.sql(sql).params(w.params());
        if (c.filtra("componente")) {
            q = q.param("f_componente", c.valores("componente"));
        }
        q.query(rs -> {
            int cu = rs.getInt("c");
            int nc = rs.getInt("nc");
            List<Object> fila = new ArrayList<>(16);
            if (periodo != null) {
                fila.add(rs.getString("periodo"));
            }
            fila.add(rs.getString("lugar"));
            fila.add(rs.getString("sigla"));
            fila.add(rs.getString("municipio"));
            fila.add(rs.getString("fuerza"));
            fila.add(rs.getString("unidad"));
            fila.add(componente(rs.getString("clave"), rs.getString("componente_nombre")));
            fila.add(rs.getInt("visitas"));
            fila.add(rs.getInt("cerradas"));
            fila.add(rs.getInt("ejecucion"));
            fila.add(rs.getInt("por_ejecutar"));
            fila.add(rs.getInt("canceladas"));
            fila.add(cu);
            fila.add(nc);
            fila.add(ReporteDetalleVisitas.porcentaje(cu, nc));
            fila.add(nc);
            destino.accept(fila);
        });
    }

    /** «C08 Almacenamiento», las obligaciones del prestador (parte OBL del resumen) o la visita agendada sin componentes. */
    static String componente(String clave, String nombre) {
        if (clave == null) {
            return SIN_COMPONENTE;
        }
        if (OBLIGACIONES.equals(clave)) {
            return "OBL Obligaciones del contrato";
        }
        return clave + (nombre == null ? "" : " " + nombre);
    }
}
