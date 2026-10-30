package co.com.agsamericas.digsa.plataforma;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo de las plantillas de correo (módulo Correos, fase C2): reúne las de cada módulo ({@link ProveedorPlantillas}) y arma el
 * correo con la versión vigente, la editada por el administrador o la de fábrica. Las ediciones son filas de
 * {@code correo_plantillas_versiones} (V91); nada se borra.
 *
 * <p>Si la versión editada no se puede leer, el correo sale con el texto de fábrica y la falla queda en el log: una plantilla nunca
 * tumba un envío.
 */
@Service
public class CatalogoPlantillas {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogoPlantillas.class);

    /** Versión vigente de una plantilla: la editada, o null si rige la de fábrica. */
    public record Version(long id, boolean restaurada, String asunto, String cuerpo, String motivo, String usuario, OffsetDateTime creado) {
    }

    private final Map<String, Plantilla> porClave = new LinkedHashMap<>();
    private final JdbcClient jdbc;
    private final Clock reloj;

    public CatalogoPlantillas(List<ProveedorPlantillas> proveedores, JdbcClient jdbc, Clock reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
        proveedores.stream().flatMap(p -> p.plantillas().stream()).sorted(Comparator.comparing(Plantilla::modulo).thenComparing(Plantilla::nombre))
                .forEach(p -> {
                    if (porClave.putIfAbsent(p.clave(), p) != null) {
                        throw new IllegalStateException("Plantilla de correo repetida: " + p.clave());
                    }
                });
    }

    public List<Plantilla> todas() {
        return List.copyOf(porClave.values());
    }

    public Optional<Plantilla> porClave(String clave) {
        return Optional.ofNullable(porClave.get(clave));
    }

    /** Arma el correo con la versión vigente de la plantilla. */
    public Plantilla.Renderizada render(Plantilla fabrica, Map<String, String> valores) {
        return vigente(fabrica).render(valores);
    }

    /** La plantilla con el texto vigente (el de fábrica si no está editada, no es editable o no se pudo leer la edición). */
    public Plantilla vigente(Plantilla fabrica) {
        if (!fabrica.editable()) {
            return fabrica;
        }
        try {
            return ultima(fabrica.clave()).filter(v -> !v.restaurada()).map(v -> fabrica.con(v.asunto(), v.cuerpo())).orElse(fabrica);
        } catch (RuntimeException e) {
            LOG.error("[correos] No se pudo leer la plantilla editada {}: sale el texto de fábrica", fabrica.clave(), e);
            return fabrica;
        }
    }

    /** Última versión registrada (editada o restaurada), si hay. */
    public Optional<Version> ultima(String clave) {
        return historial(clave, 1).stream().findFirst();
    }

    public List<Version> historial(String clave, int limite) {
        return jdbc.sql("""
                SELECT v.id, v.restaurada, v.asunto, v.cuerpo, v.motivo, u.usuario, v.creado FROM correo_plantillas_versiones v
                JOIN usuarios u ON u.id = v.usuario_id WHERE v.clave = :c ORDER BY v.id DESC LIMIT :l""")
                .param("c", clave).param("l", limite)
                .query((rs, n) -> new Version(rs.getLong("id"), rs.getBoolean("restaurada"), rs.getString("asunto"), rs.getString("cuerpo"),
                        rs.getString("motivo"), rs.getString("usuario"), rs.getObject("creado", OffsetDateTime.class)))
                .list();
    }

    /** Claves con una edición vigente (para la lista). */
    public Map<String, Version> editadas() {
        Map<String, Version> r = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT DISTINCT ON (v.clave) v.clave, v.id, v.restaurada, v.asunto, v.cuerpo, v.motivo, u.usuario, v.creado
                FROM correo_plantillas_versiones v JOIN usuarios u ON u.id = v.usuario_id ORDER BY v.clave, v.id DESC""")
                .query(rs -> {
                    if (!rs.getBoolean("restaurada")) {
                        r.put(rs.getString("clave"), new Version(rs.getLong("id"), false, rs.getString("asunto"), rs.getString("cuerpo"),
                                rs.getString("motivo"), rs.getString("usuario"), rs.getObject("creado", OffsetDateTime.class)));
                    }
                });
        return r;
    }

    /** Guarda una versión editada ya validada. */
    @Transactional
    public long guardar(Plantilla p, String asunto, String cuerpo, String motivo, long usuarioId) {
        return insertar(p, false, asunto, cuerpo, motivo, usuarioId);
    }

    /** Vuelve al texto de fábrica (agrega una versión «restaurada»). */
    @Transactional
    public long restaurar(Plantilla p, String motivo, long usuarioId) {
        return insertar(p, true, null, null, motivo, usuarioId);
    }

    private long insertar(Plantilla p, boolean restaurada, String asunto, String cuerpo, String motivo, long usuarioId) {
        if (!p.editable()) {
            throw ErrorNegocio.conflicto("La plantilla «" + p.nombre() + "» no se edita: lleva un código o enlace de un solo uso.");
        }
        return jdbc.sql("""
                INSERT INTO correo_plantillas_versiones (clave, restaurada, asunto, cuerpo, motivo, usuario_id, creado)
                VALUES (:c, :r, :a, :b, :m, :u, :ahora) RETURNING id""")
                .param("c", p.clave()).param("r", restaurada).param("a", asunto).param("b", cuerpo).param("m", motivo).param("u", usuarioId)
                .param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
    }
}
