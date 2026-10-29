package co.com.agsamericas.digsa.correos;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.ClaseReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.JdbcReportes;
import co.com.agsamericas.digsa.reportes.Opcion;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * La bitácora de envíos en el catálogo del motor de reportes (módulo Correos, C1; precisión 7 de la integradora): la descarga en Excel
 * o CSV de la pantalla Correos sale por aquí, con el encabezado, la huella y el código de verificación del motor, igual que Logs. Los
 * mismos filtros de la pantalla; los destinatarios enmascarados (Ley 1581) y sin el cuerpo del correo.
 */
@Component
class ProveedorReporteCorreos implements ProveedorReporte {

    static final String ID = "correos_envios";
    static final int MAX_FILAS = 200_000;

    static final DefinicionReporte DEFINICION = DefinicionReporte.nuevo(ID, "Bitácora de envíos de correo")
            .descripcion("Cada correo que envió la aplicación (encolado o directo): fecha, tipo, referencia, destinatarios enmascarados, asunto, estado,"
                    + " intentos y último error.")
            .grupo(GrupoReporte.SEGURIDAD).modulo("correos").clase(ClaseReporte.OPERATIVO).pantalla(Pantallas.CORREOS)
            .fuente("Bitácora de envíos de la Suite DIGSA (correos_salida)").periodo(CampoTemporal.FECHA_HORA, Set.of())
            .maxFilas(MAX_FILAS)
            .filtro(Filtro.lista("tipo", "Tipo de correo")).filtro(Filtro.lista("estado", "Estado")).filtro(Filtro.texto("destinatario", "Destinatario"))
            .columna("Fecha y hora", TipoColumna.FECHA_HORA).columna("Envío", TipoColumna.ENTERO).columna("Tipo", TipoColumna.TEXTO)
            .columna("Referencia", TipoColumna.TEXTO).columna("Destinatarios", TipoColumna.TEXTO).columna("Asunto", TipoColumna.TEXTO)
            .columna("Estado", TipoColumna.TEXTO).columna("Intentos", TipoColumna.ENTERO).columna("Enviado", TipoColumna.FECHA_HORA)
            .columna("Último error", TipoColumna.TEXTO).columna("Envío directo", TipoColumna.TEXTO).columna("Reintento de", TipoColumna.TEXTO)
            .orden("fecha y hora, de la más reciente a la más antigua, y envío").posicion(11)
            .crear();

    private final RepositorioEnvios envios;
    private final Clock reloj;

    ProveedorReporteCorreos(RepositorioEnvios envios, Clock reloj) {
        this.envios = envios;
        this.reloj = reloj;
    }

    @Override
    public DefinicionReporte definicion() {
        return DEFINICION;
    }

    @Override
    public List<Opcion> opciones(String filtro, Alcance alcance) {
        return switch (filtro) {
            case "tipo" -> ControladorCorreos.tipos(envios.origenes()).stream().map(t -> new Opcion(t.get("valor"), t.get("nombre"))).toList();
            case "estado" -> RepositorioEnvios.ESTADOS.stream().map(e -> new Opcion(e, e)).toList();
            default -> List.of();
        };
    }

    @Override
    public void filas(ConsultaReporte c, Consumer<List<Object>> destino) {
        RepositorioEnvios.Filtro f = ControladorCorreos.filtro(c.desde().toString(), c.hasta().toString(), c.valores("tipo"), c.valores("estado"),
                c.valores("destinatario").stream().findFirst().orElse(null), reloj);
        envios.recorrer(f, JdbcReportes.limite(c), x -> {
            Map<String, Object> v = new HashMap<>();
            v.put("Fecha y hora", x.creado());
            v.put("Envío", x.id());
            v.put("Tipo", TiposCorreo.nombre(x.origen()));
            v.put("Referencia", texto(x.referencia()));
            // Todos, enmascarados (en la tabla de la pantalla van los tres primeros)
            List<String> todos = new ArrayList<>(x.para());
            todos.addAll(x.copia());
            v.put("Destinatarios", String.join(", ", todos.stream().map(Correos::enmascarar).toList()));
            v.put("Asunto", texto(x.asunto()));
            v.put("Estado", x.estado());
            v.put("Intentos", x.intentos());
            v.put("Enviado", x.enviado());
            v.put("Último error", texto(x.error()));
            v.put("Envío directo", x.sincrono() ? "Sí" : "No");
            v.put("Reintento de", x.reintentoDe() == null ? "" : String.valueOf(x.reintentoDe()));
            List<Object> fila = new ArrayList<>(c.columnas().size());
            c.columnas().forEach(col -> fila.add(v.get(col)));
            destino.accept(fila);
        });
    }

    private static String texto(String s) {
        return s == null ? "" : s;
    }
}
