package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Sesion;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parámetros de seguridad que edita el Administrador (V30). Hoy: los minutos de inactividad (Anexo 5, 3.2: TIME OUT de 10 minutos
 * «parametrizable y ajustable»). Sin valor guardado vale DIGSA_SESION_MINUTOS. El filtro de la sesión lo lee en cada petición, así que
 * se guarda en memoria unos segundos: un cambio llega a todas las instancias a más tardar en {@link #SEGUNDOS_CACHE}.
 */
@Service
public class ParametrosSeguridad {

    static final String SESION_MINUTOS = "sesion_minutos";
    static final int SEGUNDOS_CACHE = 30;

    private static final Logger LOG = LoggerFactory.getLogger(ParametrosSeguridad.class);

    private record Leido(int minutos, long hastaMs) {
    }

    private final JdbcClient jdbc;
    private final ConfiguracionDigsa cfg;
    private final RegistroBitacora bitacora;
    private final Clock reloj;
    private volatile Leido cache;

    public ParametrosSeguridad(JdbcClient jdbc, ConfiguracionDigsa cfg, RegistroBitacora bitacora, Clock reloj) {
        this.jdbc = jdbc;
        this.cfg = cfg;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    /** Minutos de inactividad vigentes (1 a 480). Si la base no responde, el último valor conocido o el de la configuración. */
    public int minutosSesion() {
        long ahora = reloj.millis();
        Leido c = cache;
        if (c != null && ahora < c.hastaMs()) {
            return c.minutos();
        }
        int minutos;
        try {
            minutos = guardado().map(Sesion::minutos).orElseGet(cfg::minutosSesion);
        } catch (RuntimeException e) {
            // Falla cerrado hacia lo conocido: nunca sin plazo de inactividad
            LOG.error("[seguridad] No se pudo leer {}; se usa {}", SESION_MINUTOS, c == null ? "la configuración" : "el último valor", e);
            minutos = c == null ? cfg.minutosSesion() : c.minutos();
        }
        cache = new Leido(minutos, ahora + SEGUNDOS_CACHE * 1000L);
        return minutos;
    }

    /**
     * Cambia los minutos de inactividad; queda en la bitácora con el valor anterior y el nuevo. El anterior se lee de la base con la fila
     * bloqueada (no de la caché): dos administradores a la vez no dejan un «antes» falso.
     */
    @Transactional
    public void cambiarMinutosSesion(Integer minutos, UsuarioSesion quien) {
        if (minutos == null || minutos < 1 || minutos > Sesion.MINUTOS_MAXIMO) {
            throw ErrorNegocio.validacion("Los minutos de inactividad deben ser un número entero de 1 a " + Sesion.MINUTOS_MAXIMO + ".");
        }
        int antes = jdbc.sql("SELECT valor FROM seg_parametros WHERE clave = :c FOR UPDATE").param("c", SESION_MINUTOS).query(String.class)
                .optional().map(Sesion::minutos).orElseGet(cfg::minutosSesion);
        jdbc.sql("""
                INSERT INTO seg_parametros (clave, valor, actualizado_por, actualizado) VALUES (:c, :v, :u, :ahora)
                ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor, actualizado_por = EXCLUDED.actualizado_por, actualizado = EXCLUDED.actualizado""")
                .param("c", SESION_MINUTOS).param("v", String.valueOf(minutos)).param("u", quien.usuario())
                .param("ahora", OffsetDateTime.now(reloj)).update();
        cache = null;
        bitacora.registrar("parametro_editado", "Parámetro " + SESION_MINUTOS + (antes == minutos ? " sin cambios" : ": «" + antes + "» → «" + minutos + "»"),
                quien.id(), quien.usuario());
    }

    private Optional<String> guardado() {
        return jdbc.sql("SELECT valor FROM seg_parametros WHERE clave = :c").param("c", SESION_MINUTOS).query(String.class).optional();
    }
}
