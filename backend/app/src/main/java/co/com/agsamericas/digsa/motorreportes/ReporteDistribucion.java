package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reglas.PropuestaAgenda;
import co.com.agsamericas.digsa.reglas.Reparto;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.ClaseReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Columna;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.JdbcReportes;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Distribución auditor–punto (decisión 2026-10-20 13.3): por auditor activo, los puntos que tiene asignados o que visita en el
 * periodo, con la distancia en línea recta de su municipio base al municipio de acceso del punto y sus visitas agendadas y cerradas.
 * Justifica ante la DIGSA el reparto por cercanía y carga de la propuesta de agenda. Nombra a los auditores: solo con alcance total
 * (coordinación, administración y DIGSA), y solo se filtra por el periodo.
 */
@Component
class ReporteDistribucion implements ProveedorReporte {

    private static final DefinicionReporte DEFINICION = DefinicionReporte.nuevo("distribucion", "Distribución auditor–punto")
            .descripcion("Por auditor activo: puntos asignados o visitados, distancia desde su municipio base y visitas agendadas y cerradas.")
            .grupo(GrupoReporte.VISITAS).modulo("visitas").clase(ClaseReporte.OPERATIVO).fuente("Suite DIGSA · agenda y maestros").posicion(4)
            .periodo(CampoTemporal.FECHA, Set.<Agrupacion>of())
            .columna(Columna.personal("Auditor", Set.of(Perfil.COORDINADOR, Perfil.DIGSA))).columna("Rol", TipoColumna.TEXTO)
            .columna("Municipio base", TipoColumna.TEXTO).columna("Punto", TipoColumna.TEXTO).columna("Unidad", TipoColumna.TEXTO)
            .columna("Modalidad", TipoColumna.TEXTO).columna("Municipio del punto", TipoColumna.TEXTO).columna("Asignado al punto", TipoColumna.TEXTO)
            .columna("Distancia (km)", TipoColumna.ENTERO).columna("Agendadas", TipoColumna.ENTERO).columna("Cerradas", TipoColumna.ENTERO)
            .orden("nombre e id del auditor, nombre e id del punto").maxFilas(ReporteDetalleVisitas.MAX_FILAS).crear();

    private final JdbcClient jdbc;

    ReporteDistribucion(JdbcReportes jdbc) {
        this.jdbc = jdbc.jdbc();
    }

    @Override
    public DefinicionReporte definicion() {
        return DEFINICION;
    }

    @Override
    public boolean disponible(Alcance alcance) {
        return alcance instanceof Alcance.Todo;
    }

    @Override
    public void filas(ConsultaReporte c, Consumer<List<Object>> destino) {
        Map<String, double[]> coords = new HashMap<>();
        jdbc.sql("SELECT codigo, latitud, longitud FROM par_municipios WHERE latitud IS NOT NULL AND longitud IS NOT NULL").query(rs -> {
            coords.put(rs.getString(1).strip(), new double[] {rs.getDouble(2), rs.getDouble(3)});
        });
        boolean auditor = c.ve("Auditor");
        jdbc.sql("""
                SELECT u.nombre, coalesce(a.rol, 'otro') AS rol, mb.nombre AS base, a.municipio_base, p.nombre AS punto, p.unidad, p.modalidad, mp.nombre AS mpunto,
                       coalesce(p.llega_desde, p.municipio) AS acceso, (p.auditor_asignado = u.id) AS asignado,
                       count(v.id) FILTER (WHERE v.estado IN ('programada', 'notificada', 'reprogramada', 'ejecucion')) AS agendadas,
                       count(v.id) FILTER (WHERE v.estado = 'cerrada') AS cerradas
                FROM usuarios u JOIN auditores a ON a.usuario_id = u.id LEFT JOIN par_municipios mb ON mb.codigo = a.municipio_base
                JOIN puntos p ON p.activo AND (p.auditor_asignado = u.id OR EXISTS (SELECT 1 FROM visitas v2 JOIN visitas_auditores x2 ON x2.visita_id = v2.id
                                                                     WHERE v2.punto_id = p.id AND x2.usuario_id = u.id AND v2.fecha BETWEEN :desde AND :hasta))
                LEFT JOIN par_municipios mp ON mp.codigo = p.municipio
                LEFT JOIN visitas v ON v.punto_id = p.id AND v.fecha BETWEEN :desde AND :hasta
                  AND EXISTS (SELECT 1 FROM visitas_auditores x WHERE x.visita_id = v.id AND x.usuario_id = u.id)
                WHERE u.activo
                GROUP BY u.id, u.nombre, a.rol, mb.nombre, a.municipio_base, p.id, p.nombre, p.unidad, p.modalidad, mp.nombre, p.llega_desde, p.municipio, p.auditor_asignado
                ORDER BY u.nombre, u.id, p.nombre, p.id""" + JdbcReportes.limite(c)).param("desde", c.desde()).param("hasta", c.hasta()).query(rs -> {
            String base = rs.getString("municipio_base");
            String acceso = rs.getString("acceso");
            double[] b = base == null ? null : coords.get(base.strip());
            double[] o = acceso == null ? null : coords.get(acceso.strip());
            List<Object> fila = new ArrayList<>(11);
            if (auditor) {
                fila.add(rs.getString("nombre"));
            }
            fila.add(Reparto.ROLES.getOrDefault(rs.getString("rol"), rs.getString("rol")));
            fila.add(rs.getString("base"));
            fila.add(rs.getString("punto"));
            fila.add(rs.getString("unidad"));
            fila.add(rs.getString("modalidad"));
            fila.add(rs.getString("mpunto"));
            fila.add(rs.getBoolean("asignado") ? "Sí" : "No");
            fila.add(b == null || o == null ? null : Math.round(PropuestaAgenda.distanciaKm(b, o)));
            fila.add(rs.getInt("agendadas"));
            fila.add(rs.getInt("cerradas"));
            destino.accept(fila);
        });
    }
}
