package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Ambito;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Armado;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Conjunto;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Grupo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Dimension;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Intencion;
import co.com.agsamericas.digsa.reglas.Exportacion;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Trazabilidad de una cifra hasta los registros de origen (A5 14.9; analitica-tableros «Trazabilidad de una cifra»): la cifra se
 * identifica con la misma consulta estructurada del tablero más los filtros de su celda (variable ⇒ valor y, en las series, la etiqueta
 * del periodo). Devuelve el desglose (consulta, corte, filtros, registros, pacientes y sumas: la suma de la lista ES la cifra) y la lista
 * paginada de registros de origen seudonimizados con su cargue, archivo, línea, SHA-256 y la regla de la tabla maestra con su versión
 * (dato original → fuente → regla → dato final). Solo para los perfiles con la pantalla ANALITICA_TRAZABILIDAD (el controlador lo exige);
 * nunca el documento en claro, y sin el diagnóstico de cada registro (minimización: el CIE-10 consta en los filtros del desglose).
 *
 * <p>La lista se pagina por llave (id del hecho, el orden de carga) y el CSV sale en flujo con un cursor (fetchSize) dentro de la misma
 * transacción de solo lectura: memoria constante aunque la cifra tenga cientos de miles de registros.
 */
@Service
public class ServicioTrazaAnalitica {

    /** Filas que trae el cursor del CSV por viaje a la base. */
    static final int FETCH = 1000;
    static final String INCOMPLETO = "ARCHIVO INCOMPLETO: la descarga se interrumpió; no use este archivo y vuelva a descargarlo.";

    private final ServicioConsultaAnalitica consultas;
    private final PropiedadesTableros propiedades;
    private final NamedParameterJdbcTemplate cursor;

    ServicioTrazaAnalitica(ServicioConsultaAnalitica consultas, PropiedadesTableros propiedades, DataSource datos) {
        this.consultas = consultas;
        this.propiedades = propiedades;
        JdbcTemplate t = new JdbcTemplate(datos);
        t.setFetchSize(FETCH);
        this.cursor = new NamedParameterJdbcTemplate(t);
    }

    /** Registro de origen (seudonimizado; sin diagnóstico). */
    public record Registro(long id, LocalDate fecha, String paciente, String esm, String cumOriginal, String medicamentoOriginal, BigDecimal cantidad,
                           BigDecimal valor, long cargue, String cargueAplicado, String fuente, String archivo, String sha256, String linea,
                           Integer tablaMaestra, String regla, String estado, String datoOriginal, String fuenteReferencia, String datoFinal) {
    }

    /**
     * Desglose y página de registros.
     *
     * @param siguiente cursor de la página siguiente (id del último registro; nulo en la última)
     */
    public record Traza(Map<String, Object> desglose, List<Registro> registros, int pagina, int paginas, long total, String huella, Long siguiente) {
    }

    /** Consulta del rastreo: la del tablero, acotada al periodo de la celda si viene. */
    static ConsultaAnalitica acotar(ConsultaAnalitica q, String periodo) {
        ConsultaAnalitica r = q.conIntencion(Intencion.TRAZA);
        if (periodo == null || periodo.isBlank()) {
            return r;
        }
        LocalDate[] rango = AnaliticaConsulta.rangoPeriodo(periodo.strip())
                .orElseThrow(() -> ErrorNegocio.validacion("El periodo de la cifra no es válido."));
        LocalDate d = q.desde() != null && q.desde().isAfter(rango[0]) ? q.desde() : rango[0];
        LocalDate h = q.hasta() != null && q.hasta().isBefore(rango[1]) ? q.hasta() : rango[1];
        if (d.isAfter(h)) {
            throw ErrorNegocio.validacion("El periodo de la cifra está fuera del periodo consultado.");
        }
        return r.conPeriodo(d, h);
    }

    /**
     * Desglose de la cifra y una página de sus registros. La página se pide con {@code despues} (id del último registro de la anterior;
     * nulo = la primera) y {@code pagina} solo numera.
     */
    public Traza traza(ConsultaAnalitica consulta, String periodo, int pagina, Long despues, Alcance alcance) {
        ConsultaAnalitica q = acotar(consulta, periodo);
        int porPagina = propiedades.porPagina();
        return consultas.leer(j -> {
            Optional<ServicioConsultaAnalitica.Preparada> op = consultas.preparar(j, q, alcance);
            if (op.isEmpty()) {
                return new Traza(Map.of("mensaje", ServicioConsultaAnalitica.SIN_CARGUES), List.of(), 1, 0, 0, null, null);
            }
            ServicioConsultaAnalitica.Preparada p = op.get();
            Grupo t = total(j, p.ambito());
            int paginas = (int) Math.max(1, (t.dispensaciones() + porPagina - 1) / porPagina);
            Map<String, Object> pr = new LinkedHashMap<>();
            String sql = registrosSql(p.ambito(), pr) + (despues == null ? "" : " AND x.id > :despues") + " ORDER BY x.id LIMIT :lim";
            pr.put("despues", despues);
            pr.put("lim", porPagina + 1);
            Integer numero = p.corte().homNumero();
            List<Registro> regs = new ArrayList<>(j.sql(sql).params(pr).query((rs, n) -> registro(rs, numero)).list());
            Long siguiente = null;
            if (regs.size() > porPagina) {
                regs = new ArrayList<>(regs.subList(0, porPagina));
                siguiente = regs.getLast().id();
            }
            Map<String, Object> d = desglose(p, t, alcance, j);
            String huella = AnaliticaConsulta.huella(List.of("traza", String.valueOf(d.get("consulta")), String.valueOf(p.corte().cargueId()),
                    AnaliticaConsulta.canonico(t.gasto()), AnaliticaConsulta.canonico(t.unidades()), String.valueOf(t.dispensaciones()),
                    String.valueOf(t.pacientes())));
            return new Traza(d, regs, Math.min(Math.max(1, pagina), paginas), paginas, t.dispensaciones(), huella, siguiente);
        });
    }

    /** Total de la cifra (gasto, unidades, registros y pacientes distintos), con el mismo SQL de los tableros. */
    private static Grupo total(JdbcClient j, Ambito a) {
        Armado ar = SemanticaSql.agrupar(a, Conjunto.total(), 0, true);
        return j.sql(ar.sql()).params(ar.parametros()).query((rs, n) -> ar.leer(rs)).single();
    }

    private Map<String, Object> desglose(ServicioConsultaAnalitica.Preparada p, Grupo t, Alcance alcance, JdbcClient j) {
        Map<String, Object> d = new LinkedHashMap<>();
        Map<String, Object> consulta = new LinkedHashMap<>();
        consulta.put("tablero", p.consulta().tablero() == null ? null : p.consulta().tablero().codigo());
        Map<String, List<String>> f = new LinkedHashMap<>();
        p.ambito().filtros().forEach((k, v) -> f.put(k.parametro(), v));
        consulta.put("filtros", f);
        consulta.put("desde", p.ambito().desde());
        consulta.put("hasta", p.ambito().hasta());
        d.put("consulta", consulta);
        d.put("corte", "Corte " + AnaliticaConsulta.fechaHora(p.corte().aplicadoEn()) + " (cargue " + p.corte().cargueId() + ")");
        d.put("corteCargueId", p.corte().cargueId());
        d.put("tablaMaestra", p.corte().homNumero());
        d.put("cargues", p.corte().cargues());
        d.put("alcance", consultas.alcanceTexto(j, alcance));
        d.put("periodo", AnaliticaConsulta.fecha(p.ambito().desde()) + " – " + AnaliticaConsulta.fecha(p.ambito().hasta()));
        d.put("registros", t.dispensaciones());
        d.put("gasto", t.gasto());
        d.put("gastoTexto", AnaliticaConsulta.moneda(t.gasto()));
        d.put("unidades", t.unidades());
        d.put("unidadesTexto", AnaliticaConsulta.unidades(t.unidades()));
        d.put("pacientes", t.pacientes());
        return d;
    }

    /** SELECT de los registros de origen con su cargue y su fila de la tabla maestra vigente al corte (sin ORDER BY ni LIMIT). */
    private static String registrosSql(Ambito a, Map<String, Object> p) {
        // La tabla maestra se une siempre (la regla y el dato final de cada registro); la CIE-10 solo si un filtro la usa
        Set<Dimension> dims = Set.of(Dimension.ESTADO_HOMOLOGACION);
        String desde = SemanticaSql.desde(a, dims, List.of(), SemanticaSql.HECHOS, p).replaceFirst(" WHERE ", " JOIN ana_cargues c ON c.id = x.cargue_id WHERE ");
        return SemanticaSql.cte(dims, a.filtros(), List.of()) + """
                SELECT x.id, x.fecha, x.paciente_seudo, x.esm_codigo, x.cum_original, x.medicamento_original, x.cantidad, x.valor_total, x.cargue_id,
                       x.linea_origen, c.aplicado_en, c.fuente_texto, c.nombre_archivo, c.sha256,
                       tm.regla, tm.estado, tm.orig_descripcion, tm.orig_cum, tm.fuente_codigo, tm.fin_nombre_comercial, tm.fin_cum""" + desde;
    }

    private static Registro registro(ResultSet rs, Integer numero) throws SQLException {
        String original = juntar(rs.getString("orig_cum"), rs.getString("orig_descripcion"));
        String fin = juntar(rs.getString("fin_cum"), rs.getString("fin_nombre_comercial"));
        java.sql.Timestamp ap = rs.getTimestamp("aplicado_en");
        return new Registro(rs.getLong("id"), rs.getDate("fecha").toLocalDate(), rs.getString("paciente_seudo"), rs.getString("esm_codigo"),
                rs.getString("cum_original"), rs.getString("medicamento_original"), rs.getBigDecimal("cantidad"), rs.getBigDecimal("valor_total"),
                rs.getLong("cargue_id"), ap == null ? null : AnaliticaConsulta.fechaHora(ap.toInstant().atOffset(ZoneOffset.UTC)),
                rs.getString("fuente_texto"), rs.getString("nombre_archivo"), rs.getString("sha256"), rs.getString("linea_origen"), numero,
                rs.getString("regla") == null ? "Sin regla (sin versión de la tabla maestra)" : rs.getString("regla"),
                rs.getString("estado") == null ? "pendiente" : rs.getString("estado"), original, rs.getString("fuente_codigo"), fin);
    }

    private static String juntar(String a, String b) {
        if (a == null && b == null) {
            return null;
        }
        return a == null ? b : b == null ? a : a + " · " + b;
    }

    static final List<String> COLUMNAS_CSV = List.of("Id del hecho", "Fecha", "Seudónimo del paciente", "ESM", "CUM original", "Medicamento original",
            "Cantidad", "Valor", "Cargue", "Aplicado", "Fuente", "Archivo", "SHA-256 del archivo", "Línea", "Tabla maestra", "Regla", "Estado",
            "Dato original", "Fuente de referencia", "Dato final");

    /**
     * CSV de los registros de origen de la cifra (UTF-8 con BOM, «;», coma decimal, sin fórmulas) con los metadatos al inicio, escrito en
     * flujo con un cursor dentro de la transacción de lectura (presupuesto de descarga). Más de {@code maxRegistrosTraza} registros responde
     * 422 antes de escribir (nunca un archivo truncado). {@code antes} recibe el desglose ANTES del primer byte (la bitácora: si falla, no
     * sale nada). El archivo termina con «FIN;n registros»; si algo falla a mitad, la última línea es {@value #INCOMPLETO} y la excepción
     * sigue (sin la línea FIN). Devuelve el desglose.
     */
    public Map<String, Object> csv(ConsultaAnalitica consulta, String periodo, Alcance alcance, List<String[]> encabezado, Supplier<OutputStream> salida,
                                   Consumer<Map<String, Object>> antes) {
        ConsultaAnalitica q = acotar(consulta, periodo);
        return consultas.leer(propiedades.tiempoMaximoDescarga(), false, j -> {
            ServicioConsultaAnalitica.Preparada p = consultas.preparar(j, q, alcance)
                    .orElseThrow(() -> ErrorNegocio.validacion(ServicioConsultaAnalitica.SIN_CARGUES));
            Grupo t = total(j, p.ambito());
            if (t.dispensaciones() > propiedades.maxTraza()) {
                throw ErrorNegocio.validacion("La cifra tiene " + t.dispensaciones() + " registros de origen (máximo " + propiedades.maxTraza()
                        + " por descarga); acote los filtros o el periodo.");
            }
            Map<String, Object> d = desglose(p, t, alcance, j);
            Map<String, Object> pr = new LinkedHashMap<>();
            String sql = registrosSql(p.ambito(), pr) + " ORDER BY x.id";
            Integer numero = p.corte().homNumero();
            antes.accept(d);
            // El cursor no pasa por el cliente con plazo: se fija aquí el statement_timeout con lo que queda del presupuesto
            ServicioConsultaAnalitica.fijarPlazo(j);
            Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(salida.get(), StandardCharsets.UTF_8), 1 << 16);
            long[] escritos = {0};
            try {
                w.write('﻿');
                for (String[] l : encabezado) {
                    w.write(Exportacion.campo(l[0]) + ";" + Exportacion.campo(l[1]) + "\r\n");
                }
                w.write(Exportacion.campo("Corte") + ";" + Exportacion.campo(String.valueOf(d.get("corte"))) + "\r\n");
                w.write(Exportacion.campo("Registros procesados") + ";" + t.dispensaciones() + "\r\n");
                w.write(Exportacion.campo("Suma del valor") + ";" + AnaliticaConsulta.decimal(t.gasto()) + "\r\n\r\n");
                w.write(String.join(";", COLUMNAS_CSV.stream().map(Exportacion::campo).toList()) + "\r\n");
                cursor.query(sql, pr, rs -> {
                    Registro r = registro(rs, numero);
                    List<String> c = new ArrayList<>();
                    c.add(String.valueOf(r.id()));
                    c.add(AnaliticaConsulta.fecha(r.fecha()));
                    for (String s : new String[] {r.paciente(), r.esm(), r.cumOriginal(), r.medicamentoOriginal()}) {
                        c.add(Exportacion.campo(s == null ? "" : s));
                    }
                    c.add(AnaliticaConsulta.unidades(r.cantidad()));
                    c.add(AnaliticaConsulta.decimal(r.valor()));
                    c.add(String.valueOf(r.cargue()));
                    for (String s : new String[] {r.cargueAplicado(), r.fuente(), r.archivo(), r.sha256(), r.linea(),
                        r.tablaMaestra() == null ? "" : "v" + r.tablaMaestra(), r.regla(), r.estado(), r.datoOriginal(), r.fuenteReferencia(), r.datoFinal()}) {
                        c.add(Exportacion.campo(s == null ? "" : s));
                    }
                    try {
                        w.write(String.join(";", c));
                        w.write("\r\n");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    escritos[0]++;
                });
                if (escritos[0] != t.dispensaciones()) {
                    throw new IllegalStateException("El CSV de la traza escribió " + escritos[0] + " de " + t.dispensaciones() + " registros");
                }
                w.write("FIN;" + escritos[0] + " registros\r\n");
                w.flush();
            } catch (IOException | RuntimeException e) {
                incompleto(w);
                throw e instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) e;
            }
            return d;
        });
    }

    /** Deja la marca de archivo incompleto (si el cliente sigue ahí); nunca oculta la falla original. */
    private static void incompleto(Writer w) {
        try {
            w.write("\r\n" + INCOMPLETO + "\r\n");
            w.flush();
        } catch (IOException | RuntimeException e) {
            // El cliente cortó la conexión: no hay a quién avisarle; la falla original sigue y queda en la bitácora
        }
    }
}
