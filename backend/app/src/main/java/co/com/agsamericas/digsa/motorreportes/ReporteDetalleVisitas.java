package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Checklist;
import co.com.agsamericas.digsa.reglas.EstadosVisita;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.ClaseReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Columna;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.JdbcReportes;
import co.com.agsamericas.digsa.reportes.Opcion;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Detalle de visitas (Anexo 5, prueba 13): lugar, inicio y fin reales, número, componentes auditados, objeto, hallazgos y auditor,
 * filtrable por periodo y lugar. Una fila por visita; los componentes y los hallazgos del último acta enviada van en una celda
 * separados por « · » (decisión de la sesión 1, 2026-10-22). Los resultados y los hallazgos solo salen de las visitas cerradas: una
 * visita sin acta enviada, en curso o reabierta sale con los resultados vacíos (19.9), como en los consolidados y el tablero. El
 * auditor solo lo ve la coordinación: los perfiles de consulta no ven el equipo (Ley 1581).
 */
@Component
class ReporteDetalleVisitas implements ProveedorReporte {

    static final String AUDITOR = "Auditor líder";
    private static final DefinicionReporte DEFINICION = DefinicionReporte.nuevo("detalle", "Detalle de visitas")
            .descripcion("Una fila por visita: inicio y fin reales, objeto, componentes auditados, resultados y hallazgos del acta.")
            .grupo(GrupoReporte.VISITAS).modulo("visitas").clase(ClaseReporte.OPERATIVO).fuente(FiltrosVisitas.FUENTE).posicion(1)
            .periodo(CampoTemporal.FECHA, Set.<Agrupacion>of())
            .filtro(FiltrosVisitas.LUGAR).filtro(FiltrosVisitas.MUNICIPIO).filtro(FiltrosVisitas.FUERZA).filtro(FiltrosVisitas.UNIDAD)
            .filtro(FiltrosVisitas.REGIONAL).filtro(FiltrosVisitas.ESTADO).filtro(FiltrosVisitas.COMPONENTE)
            .columna("Número", TipoColumna.TEXTO).columna("Fecha programada", TipoColumna.FECHA).columna("Inicio real", TipoColumna.FECHA_HORA)
            .columna("Fin real", TipoColumna.FECHA_HORA).columna("Estado", TipoColumna.TEXTO).columna("Tipo de visita", TipoColumna.TEXTO)
            .columna("Lugar", TipoColumna.TEXTO).columna("Municipio", TipoColumna.TEXTO).columna("Fuerza", TipoColumna.TEXTO)
            .columna("Unidad", TipoColumna.TEXTO).columna("Objeto", TipoColumna.TEXTO).columna("Componentes", TipoColumna.TEXTO)
            .columna(Columna.personal(AUDITOR, Set.of(Perfil.COORDINADOR)))
            .columna("Cumple", TipoColumna.ENTERO).columna("No cumple", TipoColumna.ENTERO).columna("No aplica", TipoColumna.ENTERO)
            .columna("% cumplimiento", TipoColumna.PORCENTAJE).columna("Hallazgos", TipoColumna.ENTERO)
            .columna("Descripción de los hallazgos", TipoColumna.TEXTO)
            .orden("fecha programada e id de la visita").crear();

    private final JdbcClient jdbc;
    private final FiltrosVisitas filtros;

    ReporteDetalleVisitas(JdbcReportes jdbc, FiltrosVisitas filtros) {
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
        String sql = """
                SELECT v.numero, v.fecha, e.inicio_real, e.fin_real, v.estado, t.nombre AS tipo, p.nombre AS lugar, m.nombre AS municipio, p.fuerza,
                       p.unidad, e.objeto, u.nombre AS lider, r.c, r.nc, r.na, r.hallazgos,
                       (SELECT string_agg(vc.componente || coalesce(' ' || cc.nombre, ''), ' · ' ORDER BY vc.componente)
                        FROM visitas_componentes vc
                        LEFT JOIN chk_componentes cc ON cc.codigo = vc.componente
                             AND cc.version = coalesce(v.checklist_version, (SELECT cv.version FROM chk_versiones cv WHERE cv.vigente LIMIT 1))
                        WHERE vc.visita_id = v.id) AS componentes,
                       (SELECT string_agg(h.codigo || ': ' || coalesce(h.hallazgo, h.texto), ' · ' ORDER BY h.codigo, h.id)
                        FROM visitas_hallazgos h WHERE h.acta_id = ua.id) AS textos
                FROM visitas v JOIN puntos p ON p.id = v.punto_id JOIN par_tipos_visita t ON t.codigo = v.tipo JOIN usuarios u ON u.id = v.auditor_id
                LEFT JOIN par_municipios m ON m.codigo = p.municipio
                LEFT JOIN visitas_ejecucion e ON e.visita_id = v.id
                LEFT JOIN visitas_resumen r ON r.visita_id = v.id AND v.estado = 'cerrada'
                LEFT JOIN LATERAL (SELECT a.id FROM visitas_actas a WHERE a.visita_id = v.id AND a.estado IN ('enviada', 'parcial')
                                   ORDER BY a.ronda DESC LIMIT 1) ua ON v.estado = 'cerrada'""" + w.sql()
                + (c.filtra("componente") ? " AND EXISTS (SELECT 1 FROM visitas_componentes x WHERE x.visita_id = v.id AND x.componente IN (:f_componente))" : "")
                + " ORDER BY v.fecha, v.id" + JdbcReportes.limite(c);
        var q = jdbc.sql(sql).params(w.params());
        if (c.filtra("componente")) {
            q = q.param("f_componente", c.valores("componente"));
        }
        boolean auditor = c.ve(AUDITOR);
        q.query(rs -> {
            List<Object> fila = new ArrayList<>(19);
            fila.add(rs.getString("numero"));
            fila.add(rs.getDate("fecha").toLocalDate());
            fila.add(rs.getObject("inicio_real", OffsetDateTime.class));
            fila.add(rs.getObject("fin_real", OffsetDateTime.class));
            fila.add(EstadosVisita.NOMBRES.get(rs.getString("estado")));
            fila.add(rs.getString("tipo"));
            fila.add(rs.getString("lugar"));
            fila.add(rs.getString("municipio"));
            fila.add(rs.getString("fuerza"));
            fila.add(rs.getString("unidad"));
            fila.add(rs.getString("objeto"));
            fila.add(rs.getString("componentes"));
            if (auditor) {
                fila.add(rs.getString("lider"));
            }
            Integer cu = (Integer) rs.getObject("c");
            Integer nc = (Integer) rs.getObject("nc");
            fila.add(cu);
            fila.add(nc);
            fila.add(rs.getObject("na"));
            fila.add(cu == null || nc == null ? null : porcentaje(cu, nc));
            fila.add(rs.getObject("hallazgos"));
            fila.add(rs.getString("textos"));
            destino.accept(fila);
        });
    }

    /** % de cumplimiento con un decimal, como en el acta; BigDecimal para que la huella no dependa de la representación del double. */
    static BigDecimal porcentaje(int c, int nc) {
        Double p = Checklist.cumplimiento(c, nc);
        return p == null ? null : BigDecimal.valueOf(p);
    }
}
