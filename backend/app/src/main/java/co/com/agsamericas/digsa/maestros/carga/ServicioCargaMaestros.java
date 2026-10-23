package co.com.agsamericas.digsa.maestros.carga;

import co.com.agsamericas.digsa.identidad.RepositorioUsuarios;
import co.com.agsamericas.digsa.identidad.ServicioClaves;
import co.com.agsamericas.digsa.identidad.Usuario;
import co.com.agsamericas.digsa.maestros.carga.InformeCarga.Accion;
import co.com.agsamericas.digsa.maestros.carga.InformeCarga.Linea;
import co.com.agsamericas.digsa.maestros.carga.LectorPlantillas.Lectura;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Catalogos;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Existentes;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Fila;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Municipio;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.PuntoExistente;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Resultado;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Tipo;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.UsuarioExistente;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.sql.Array;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carga inicial de auditores, puntos y usuarios (despliegue-carga, parte B). Simular (por defecto) valida, resuelve las referencias
 * y ejecuta los cambios en una transacción que siempre se revierte, para que también salgan los choques que solo ve la base. Aplicar
 * hace lo mismo y confirma solo si NO hay ninguna fila con error (todo o nada, GMI P77 f). Idempotente por clave natural; una celda
 * vacía no borra; nada se borra ni se inactiva por no venir; los perfiles se agregan. Dos cargas no se cruzan: candado consultivo.
 * Los enlaces de bienvenida salen después de confirmar, de a uno y con pausa.
 */
@Service
public class ServicioCargaMaestros {

    /** Candado consultivo de la carga ("DIGSACML"). */
    static final long CANDADO = 0x4449475341434D4CL;
    static final String ASUNTO_BIENVENIDA = "Bienvenido a la Suite DIGSA";
    /** Datos personales (Ley 1581): en la bitácora se dice que cambiaron, sin el valor. Documento y teléfono van enmascarados. */
    private static final Set<String> SIN_VALOR = Set.of("nombre", "correo", "contacto", "correos");

    public enum Modo {
        SIMULAR, APLICAR
    }

    public record Responsable(long id, String usuario) {
    }

    /** Otra carga tiene el candado (código 2). */
    public static class OtraCarga extends RuntimeException {
        OtraCarga() {
            super("Hay otra carga en curso.");
        }
    }

    /** Problema de una fila que se descubre al escribir (no en las reglas). */
    static class FilaInvalida extends RuntimeException {
        FilaInvalida(String motivo) {
            super(motivo);
        }
    }

    private record Evento(String tipo, String descripcion) {
    }

    private final JdbcClient jdbc;
    private final RepositorioUsuarios usuarios;
    private final ServicioClaves claves;
    private final RegistroBitacora bitacora;
    private final PlatformTransactionManager transacciones;
    private final Clock reloj;

    public ServicioCargaMaestros(JdbcClient jdbc, RepositorioUsuarios usuarios, ServicioClaves claves, RegistroBitacora bitacora,
                                 PlatformTransactionManager transacciones, Clock reloj) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.claves = claves;
        this.bitacora = bitacora;
        this.transacciones = transacciones;
        this.reloj = reloj;
    }

    /** El responsable debe ser un Administrador activo: queda como autor en la bitácora. */
    public Optional<Responsable> responsable(String usuario) {
        if (usuario == null || usuario.isBlank()) {
            return Optional.empty();
        }
        return usuarios.porUsuario(usuario.strip().toLowerCase()).filter(Usuario::activo).filter(u -> u.perfiles().contains(Perfil.ADMINISTRADOR))
                .map(u -> new Responsable(u.id(), u.usuario()));
    }

    /** Corrida de la carga. El código queda en el informe: 0, 3 (errores; nada escrito) o 4 (cargado; algún correo no salió). */
    public InformeCarga ejecutar(Lectura lectura, Modo modo, Responsable quien, boolean enviarEnlaces, long pausaMs) {
        InformeCarga informe = new InformeCarga(modo == Modo.APLICAR ? "aplicar" : "simular");
        for (String a : lectura.avisos()) {
            informe.agregar(new Linea(null, null, null, null, Accion.AVISO, null, a));
        }
        Corrida c = new Corrida(modo, quien, informe);
        TransactionTemplate tx = new TransactionTemplate(transacciones);
        tx.executeWithoutResult(estado -> c.ejecutar(lectura, estado));
        if (c.rechazada) {
            informe.codigo(3);
        } else if (modo == Modo.APLICAR) {
            informe.escrito(true);
            for (Evento e : c.eventos) {
                bitacora.registrar(e.tipo(), e.descripcion(), quien.id(), quien.usuario());
            }
            if (enviarEnlaces) {
                enviarBienvenidas(c.nuevos, informe, pausaMs);
            }
            informe.codigo(informe.cuenta(Accion.CORREO_NO_ENVIADO) > 0 ? 4 : 0);
        } else {
            informe.codigo(0);
        }
        resumenBitacora(lectura, modo, quien, informe);
        return informe;
    }

    /** {@code --digsa.carga.enlaces=pendientes}: enlace a los usuarios activos que nunca definieron su contraseña. */
    public InformeCarga enviarPendientes(Responsable quien, long pausaMs) {
        InformeCarga informe = new InformeCarga("enlaces pendientes");
        List<Long> ids = jdbc.sql("SELECT id FROM usuarios WHERE activo AND NOT clave_definida AND NOT clave_inicial ORDER BY id")
                .query(Long.class).list();
        enviarBienvenidas(ids, informe, pausaMs);
        long enviados = ids.size() - informe.cuenta(Accion.CORREO_NO_ENVIADO);
        bitacora.registrar("carga_maestros", "Carga de maestros (cli, enlaces pendientes) por " + quien.usuario() + ": enlaces enviados "
                + enviados + ", correos no enviados " + informe.cuenta(Accion.CORREO_NO_ENVIADO), quien.id(), quien.usuario());
        informe.codigo(informe.cuenta(Accion.CORREO_NO_ENVIADO) > 0 ? 4 : 0);
        return informe;
    }

    private void enviarBienvenidas(List<Long> ids, InformeCarga informe, long pausaMs) {
        boolean primero = true;
        for (Long id : ids) {
            Usuario u = usuarios.porId(id).orElse(null);
            if (u == null || !u.activo()) {
                continue;
            }
            if (!primero && pausaMs > 0) {
                try {
                    Thread.sleep(pausaMs); // sin disparar los topes de envío del servidor de correo
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            primero = false;
            ServicioClaves.EnvioEnlace e = claves.enviarEnlace(u, ASUNTO_BIENVENIDA,
                    "Se creó su usuario «" + u.usuario() + "» en la Suite DIGSA. Defina su contraseña con el siguiente enlace.", false);
            if (!e.correoOk()) {
                informe.agregar(new Linea(null, "usuarios", null, u.correo(), Accion.CORREO_NO_ENVIADO, null,
                        "el servidor de correo no lo aceptó (" + Bitacora.sinCorreos(Objects.toString(e.error(), "sin detalle"))
                                + "); use «Restablecer contraseña» cuando el correo funcione"));
            }
        }
    }

    private void resumenBitacora(Lectura lectura, Modo modo, Responsable quien, InformeCarga informe) {
        String archivos = lectura.archivos().stream().map(a -> a.nombre() + " sha256=" + a.sha256()).collect(Collectors.joining(", "));
        String estado = informe.codigo() == 3 ? "rechazada, sin escribir; " : "";
        String desc = "Carga de maestros (cli, " + informe.modo() + ") por " + quien.usuario() + ": " + estado + informe.resumen() + ". Archivos: "
                + archivos;
        bitacora.registrar(modo == Modo.APLICAR ? "carga_maestros" : "carga_maestros_simulada", desc, quien.id(), quien.usuario());
    }

    // ---------- Una corrida, dentro de su transacción ----------

    private final class Corrida {
        private final Modo modo;
        private final Responsable quien;
        private final InformeCarga informe;
        private final Instant ahora;
        private final List<Evento> eventos = new ArrayList<>();
        private final List<Long> nuevos = new ArrayList<>();
        private final Map<Integer, Long> puntosPorFila = new HashMap<>();
        private boolean rechazada;
        /** En simulación no se gasta BCrypt: la transacción se revierte. */
        private final String claveSimulada = "{simulada}";

        Corrida(Modo modo, Responsable quien, InformeCarga informe) {
            this.modo = modo;
            this.quien = quien;
            this.informe = informe;
            this.ahora = reloj.instant();
        }

        void ejecutar(Lectura lectura, TransactionStatus estado) {
            Boolean candado = jdbc.sql("SELECT pg_try_advisory_xact_lock(:k)").param("k", CANDADO).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(candado)) {
                throw new OtraCarga();
            }
            Resultado r = ImportadorMaestros.validar(lectura.hojas(), catalogos(), existentes());
            for (var e : r.desconocidas().entrySet()) {
                informe.agregar(new Linea(null, e.getKey().clave(), null, null, Accion.AVISO, null,
                        "Columnas que no se usan en " + e.getKey().clave() + ": " + String.join(", ", e.getValue()) + "."));
            }
            if (r.archivoRechazado()) {
                for (String m : r.erroresArchivo()) {
                    informe.agregar(new Linea(null, null, null, null, Accion.ERROR, null, m));
                }
                rechazada = true;
                estado.setRollbackOnly();
                return;
            }
            for (Fila f : r.filas()) {
                if (f.accion() == ImportadorMaestros.Accion.ERROR) {
                    informe.agregar(new Linea(f.archivo(), f.tipo().clave(), f.numero(), f.clave(), Accion.ERROR, null,
                            f.problemas().stream().map(Object::toString).collect(Collectors.joining("; "))));
                    continue;
                }
                Object punto = estado.createSavepoint();
                try {
                    Linea l = switch (f.tipo()) {
                        case AUDITORES -> f.accion() == ImportadorMaestros.Accion.NUEVO ? auditorNuevo(f) : auditorExistente(f);
                        case PUNTOS -> f.accion() == ImportadorMaestros.Accion.NUEVO ? puntoNuevo(f) : puntoExistente(f);
                        case USUARIOS -> f.accion() == ImportadorMaestros.Accion.NUEVO ? usuarioNuevo(f) : usuarioExistente(f);
                    };
                    estado.releaseSavepoint(punto);
                    informe.agregar(l);
                } catch (FilaInvalida | DataAccessException e) {
                    estado.rollbackToSavepoint(punto);
                    String motivo = e instanceof FilaInvalida ? e.getMessage()
                            : "base: choca con otro registro (sigla, nombre y unidad, usuario o correo ya registrados)";
                    informe.agregar(new Linea(f.archivo(), f.tipo().clave(), f.numero(), f.clave(), Accion.ERROR, null, motivo));
                }
            }
            rechazada = informe.cuenta(Accion.ERROR) > 0;
            if (modo == Modo.SIMULAR || rechazada) {
                estado.setRollbackOnly();
            }
        }

        private Linea linea(Fila f, Accion a, Collection<String> campos, String motivo) {
            return new Linea(f.archivo(), f.tipo().clave(), f.numero(), f.clave(), a, campos == null ? null : String.join(", ", campos), motivo);
        }

        private String clave() {
            return modo == Modo.APLICAR ? claves.claveAleatoriaCodificada() : claveSimulada;
        }

        // ----- Auditores -----

        private Linea auditorNuevo(Fila f) {
            Map<String, Object> d = f.datos();
            String usuario = (String) d.get("usuario");
            long id = usuarios.crear((String) d.get("nombre"), (String) d.get("documento"), (String) d.get("telefono"), usuario,
                    (String) d.get("correo"), clave(), null, null, null, ahora);
            usuarios.agregarPerfil(id, Perfil.AUDITOR);
            jdbc.sql("INSERT INTO auditores (usuario_id, rol, municipio_base, actualizado) VALUES (:id, :rol, :m, :ahora)")
                    .param("id", id).param("rol", d.get("rol")).param("m", d.get("municipioBase")).param("ahora", odt()).update();
            nuevos.add(id);
            eventos.add(new Evento("auditor_creado", "Auditor " + usuario + " (usuario " + id + ") creado por la carga de maestros; rol "
                    + d.get("rol") + (d.get("municipioBase") == null ? "" : ", municipio base " + d.get("municipioBase"))));
            return linea(f, Accion.NUEVO, null, "usuario nuevo con el perfil Auditor");
        }

        /** Usuario existente: se le agrega el perfil Auditor sin tocar nombre ni correo; documento y teléfono solo si no tenía. */
        private Linea auditorExistente(Fila f) {
            Map<String, Object> d = f.datos();
            Usuario u = usuarios.porId(f.id()).orElseThrow(() -> new FilaInvalida("correo: el usuario ya no existe"));
            usuarios.bloquear(u.id());
            Map<String, Object> antes = new LinkedHashMap<>();
            Map<String, Object> despues = new LinkedHashMap<>();
            List<String> campos = new ArrayList<>();
            if (!u.perfiles().contains(Perfil.AUDITOR)) {
                usuarios.agregarPerfil(u.id(), Perfil.AUDITOR);
                campos.add("perfiles");
                antes.put("perfiles", nombres(u.perfiles()));
                Set<Perfil> con = EnumSet.copyOf(u.perfiles().isEmpty() ? EnumSet.noneOf(Perfil.class) : u.perfiles());
                con.add(Perfil.AUDITOR);
                despues.put("perfiles", nombres(con));
            }
            String documento = u.documento() == null ? (String) d.get("documento") : u.documento();
            String telefono = u.telefono() == null ? (String) d.get("telefono") : u.telefono();
            if (!Objects.equals(documento, u.documento()) || !Objects.equals(telefono, u.telefono())) {
                usuarios.actualizar(u.id(), u.nombre(), documento, telefono, u.usuario(), u.correo(), u.alcanceFuerza(), u.alcancePunto(),
                        u.alcanceRegional(), ahora);
                cambioEnmascarado(campos, antes, despues, "documento", u.documento(), documento);
                cambioEnmascarado(campos, antes, despues, "telefono", u.telefono(), telefono);
            }
            Map<String, Object> actual = jdbc.sql("SELECT rol, municipio_base FROM auditores WHERE usuario_id = :id FOR UPDATE").param("id", u.id())
                    .query().listOfRows().stream().findFirst().orElse(null);
            String rol = actual == null || Boolean.TRUE.equals(d.get("rolExplicito")) ? (String) d.get("rol") : (String) actual.get("rol");
            String municipio = d.get("municipioBase") != null ? (String) d.get("municipioBase") : actual == null ? null : (String) actual.get("municipio_base");
            if (actual == null || !Objects.equals(rol, actual.get("rol")) || !Objects.equals(municipio, actual.get("municipio_base"))) {
                jdbc.sql("""
                        INSERT INTO auditores (usuario_id, rol, municipio_base, actualizado) VALUES (:id, :rol, :m, :ahora)
                        ON CONFLICT (usuario_id) DO UPDATE SET rol = EXCLUDED.rol, municipio_base = EXCLUDED.municipio_base, actualizado = EXCLUDED.actualizado""")
                        .param("id", u.id()).param("rol", rol).param("m", municipio).param("ahora", odt()).update();
                cambio(campos, antes, despues, "rol", actual == null ? null : actual.get("rol"), rol);
                cambio(campos, antes, despues, "municipio_base", actual == null ? null : actual.get("municipio_base"), municipio);
            }
            String conserva = conserva(u.perfiles(), Set.of(Perfil.AUDITOR));
            if (campos.isEmpty()) {
                return linea(f, Accion.SIN_CAMBIOS, null, conserva);
            }
            eventos.add(new Evento("auditor_editado", "Auditor " + u.id() + " (carga de maestros): " + Bitacora.cambios(antes, despues, SIN_VALOR)));
            return linea(f, Accion.ACTUALIZAR, campos, conserva);
        }

        // ----- Puntos -----

        private Linea puntoNuevo(Fila f) {
            Map<String, Object> d = f.datos();
            long id = jdbc.sql("""
                    INSERT INTO puntos (sigla, nombre, unidad, fuerza, modalidad, nit, municipio, direccion, contacto, correos, regional_id,
                      llega_desde, auditor_asignado, exige_qf, activo, creado_por, fuente, creado, actualizado)
                    VALUES (:sigla, :nombre, :unidad, :fuerza, :modalidad, :nit, :municipio, :direccion, :contacto, :correos, :regional,
                      :llega, :auditor, :qf, :activo, :quien, 'Carga inicial de maestros', :ahora, :ahora)
                    RETURNING id""")
                    .param("sigla", d.get("sigla")).param("nombre", d.get("nombre")).param("unidad", d.get("unidad")).param("fuerza", d.get("fuerza"))
                    .param("modalidad", d.get("modalidad")).param("nit", d.get("nit")).param("municipio", d.get("municipio"))
                    .param("direccion", d.get("direccion")).param("contacto", d.get("contacto"))
                    .param("correos", correos(d.get("correos")).toArray(String[]::new)).param("regional", d.get("regionalId"))
                    .param("llega", d.get("llegaDesde")).param("auditor", auditor(d.get("auditorAsignado")))
                    .param("qf", Boolean.TRUE.equals(d.get("exigeQf"))).param("activo", !Boolean.FALSE.equals(d.get("activo")))
                    .param("quien", quien.usuario()).param("ahora", odt()).query(Long.class).single();
            puntosPorFila.put(f.numero(), id);
            eventos.add(new Evento("punto_creado", "Punto " + id + " creado por la carga de maestros: «" + Bitacora.cortar((String) d.get("nombre"), 80)
                    + "» (" + d.get("unidad") + ", " + d.get("fuerza") + ")"));
            return linea(f, Accion.NUEVO, null, null);
        }

        private static final Map<String, String> COLUMNA_PUNTO = Map.ofEntries(Map.entry("sigla", "sigla"), Map.entry("nombre", "nombre"),
                Map.entry("unidad", "unidad"), Map.entry("fuerza", "fuerza"), Map.entry("modalidad", "modalidad"), Map.entry("nit", "nit"),
                Map.entry("municipio", "municipio"), Map.entry("direccion", "direccion"), Map.entry("contacto", "contacto"),
                Map.entry("correos", "correos"), Map.entry("exigeQf", "exige_qf"), Map.entry("regionalId", "regional_id"),
                Map.entry("llegaDesde", "llega_desde"), Map.entry("auditorAsignado", "auditor_asignado"), Map.entry("activo", "activo"));

        /** Solo se escriben las celdas con valor que cambian lo guardado. */
        private Linea puntoExistente(Fila f) {
            Map<String, Object> d = f.datos();
            Map<String, Object> actual = jdbc.sql("""
                    SELECT sigla, nombre, unidad, fuerza, modalidad, nit, municipio, direccion, contacto, correos, exige_qf, regional_id,
                           llega_desde, auditor_asignado, activo FROM puntos WHERE id = :id FOR UPDATE""")
                    .param("id", f.id()).query().listOfRows().stream().findFirst().orElseThrow(() -> new FilaInvalida("sigla: el punto ya no existe"));
            Map<String, Object> nuevos = new LinkedHashMap<>();
            Map<String, Object> antes = new LinkedHashMap<>();
            Map<String, Object> despues = new LinkedHashMap<>();
            List<String> campos = new ArrayList<>();
            for (var e : d.entrySet()) {
                String col = COLUMNA_PUNTO.get(e.getKey());
                if (col == null) {
                    continue;
                }
                Object valor = switch (e.getKey()) {
                    case "correos" -> correos(e.getValue());
                    case "auditorAsignado" -> auditor(e.getValue());
                    default -> e.getValue();
                };
                Object previo = col.equals("correos") ? arreglo(actual.get(col)) : actual.get(col);
                if (!igual(previo, valor)) {
                    nuevos.put(col, valor);
                    campos.add(e.getKey());
                    antes.put(e.getKey(), previo);
                    despues.put(e.getKey(), valor);
                }
            }
            if (Boolean.FALSE.equals(nuevos.get("activo"))) {
                long esm = jdbc.sql("SELECT count(*) FROM usuarios WHERE alcance_punto = :id AND activo").param("id", f.id()).query(Long.class).single();
                if (esm > 0) {
                    throw new FilaInvalida("activo: el punto es el alcance de " + esm + " usuarios ESM activos; cámbielos antes de inactivarlo");
                }
            }
            if (nuevos.isEmpty()) {
                puntosPorFila.put(f.numero(), f.id());
                return linea(f, Accion.SIN_CAMBIOS, null, null);
            }
            StringBuilder sql = new StringBuilder("UPDATE puntos SET actualizado = :ahora");
            nuevos.keySet().forEach(c -> sql.append(", ").append(c).append(" = :").append(c));
            JdbcClient.StatementSpec q = jdbc.sql(sql.append(" WHERE id = :id").toString()).param("ahora", odt()).param("id", f.id());
            for (var e : nuevos.entrySet()) {
                q = q.param(e.getKey(), e.getValue() instanceof List<?> l ? l.toArray(String[]::new) : e.getValue());
            }
            q.update();
            puntosPorFila.put(f.numero(), f.id());
            eventos.add(new Evento("punto_editado", "Punto " + f.id() + " (carga de maestros): " + Bitacora.cambios(antes, despues, SIN_VALOR)));
            return linea(f, Accion.ACTUALIZAR, campos, null);
        }

        // ----- Usuarios -----

        private Long puntoDe(Map<String, Object> d) {
            if (d.get("alcancePunto") instanceof Long id) {
                return id;
            }
            if (d.get("alcancePuntoFila") instanceof Integer fila) {
                Long id = puntosPorFila.get(fila);
                if (id == null) {
                    throw new FilaInvalida("alcance_punto: el punto de la fila " + fila + " de la hoja de puntos no se pudo cargar");
                }
                return id;
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private Linea usuarioNuevo(Fila f) {
            Map<String, Object> d = f.datos();
            String usuario = (String) d.get("usuario");
            List<Perfil> perfiles = (List<Perfil>) d.get("perfiles");
            long id = usuarios.crear((String) d.get("nombre"), (String) d.get("documento"), (String) d.get("telefono"), usuario,
                    (String) d.get("correo"), clave(), (String) d.get("alcanceFuerza"), puntoDe(d), (Long) d.get("alcanceRegional"), ahora);
            usuarios.reemplazarPerfiles(id, perfiles);
            if (Boolean.FALSE.equals(d.get("activo"))) {
                usuarios.cambiarEstado(id, false, ahora);
            } else {
                nuevos.add(id);
            }
            eventos.add(new Evento("usuario_creado", "Usuario " + usuario + " creado por la carga de maestros; perfiles: "
                    + perfiles.stream().map(p -> String.valueOf(p.id())).collect(Collectors.joining(","))));
            return linea(f, Accion.NUEVO, null, Boolean.FALSE.equals(d.get("activo")) ? "creado inactivo: no recibe el enlace" : null);
        }

        /** Datos con valor que cambian; perfiles que se agregan (los que tenía se conservan); activo solo si la columna trae valor. */
        @SuppressWarnings("unchecked")
        private Linea usuarioExistente(Fila f) {
            Map<String, Object> d = f.datos();
            long id = f.id() != null ? f.id() : jdbc.sql("SELECT id FROM usuarios WHERE correo = :c").param("c", d.get("correo")).query(Long.class)
                    .optional().orElseThrow(() -> new FilaInvalida("correo: el usuario de la hoja de auditores no se pudo cargar"));
            usuarios.bloquear(id);
            Usuario u = usuarios.porId(id).orElseThrow();
            Map<String, Object> antes = new LinkedHashMap<>();
            Map<String, Object> despues = new LinkedHashMap<>();
            List<String> campos = new ArrayList<>();
            String nombre = valor(d, "nombre", u.nombre());
            String documento = valor(d, "documento", u.documento());
            String telefono = valor(d, "telefono", u.telefono());
            String fuerza = valor(d, "alcanceFuerza", u.alcanceFuerza());
            Long punto = Optional.ofNullable(puntoDe(d)).orElse(u.alcancePunto());
            Long regional = d.get("alcanceRegional") instanceof Long r ? r : u.alcanceRegional();
            cambio(campos, antes, despues, "nombre", u.nombre(), nombre);
            cambioEnmascarado(campos, antes, despues, "documento", u.documento(), documento);
            cambioEnmascarado(campos, antes, despues, "telefono", u.telefono(), telefono);
            cambio(campos, antes, despues, "alcance_fuerza", u.alcanceFuerza(), fuerza);
            cambio(campos, antes, despues, "alcance_punto", u.alcancePunto(), punto);
            cambio(campos, antes, despues, "alcance_regional", u.alcanceRegional(), regional);
            boolean datos = !campos.isEmpty();
            Set<Perfil> agregar = EnumSet.noneOf(Perfil.class);
            agregar.addAll((List<Perfil>) d.get("perfiles"));
            agregar.removeAll(u.perfiles());
            if (!agregar.isEmpty()) {
                Set<Perfil> con = EnumSet.noneOf(Perfil.class);
                con.addAll(u.perfiles());
                con.addAll(agregar);
                cambio(campos, antes, despues, "perfiles", nombres(u.perfiles()), nombres(con));
            }
            Boolean activo = (Boolean) d.get("activo");
            boolean cambiaActivo = activo != null && activo != u.activo();
            if (cambiaActivo) {
                cambio(campos, antes, despues, "activo", u.activo(), activo);
            }
            String conserva = conserva(u.perfiles(), Set.of());
            if (campos.isEmpty()) {
                return linea(f, Accion.SIN_CAMBIOS, null, conserva);
            }
            if (datos) {
                usuarios.actualizar(id, nombre, documento, telefono, u.usuario(), u.correo(), fuerza, punto, regional, ahora);
            }
            agregar.forEach(p -> usuarios.agregarPerfil(id, p));
            if (cambiaActivo) {
                usuarios.cambiarEstado(id, activo, ahora);
                if (!activo) {
                    usuarios.anularEnlace(id);
                    usuarios.cerrarCualquierSesion(id);
                }
            }
            eventos.add(new Evento(cambiaActivo && campos.size() == 1 ? "usuario_estado" : "usuario_editado",
                    "Usuario " + u.usuario() + " editado por la carga de maestros: " + Bitacora.cambios(antes, despues, SIN_VALOR)));
            return linea(f, Accion.ACTUALIZAR, campos, conserva);
        }

        private String conserva(Set<Perfil> tenia, Set<Perfil> excepto) {
            List<String> n = tenia.stream().filter(p -> !excepto.contains(p)).sorted().map(Perfil::nombre).toList();
            return n.isEmpty() ? null : "perfiles que conserva: " + String.join(", ", n);
        }

        private Long auditor(Object correo) {
            if (correo == null) {
                return null;
            }
            return jdbc.sql("SELECT id FROM usuarios WHERE correo = :c").param("c", correo).query(Long.class).optional()
                    .orElseThrow(() -> new FilaInvalida("auditor_asignado: el auditor de la hoja de auditores no se pudo cargar"));
        }

        private OffsetDateTime odt() {
            return OffsetDateTime.ofInstant(ahora, reloj.getZone());
        }
    }

    // ---------- Catálogos y existentes ----------

    private Catalogos catalogos() {
        Map<String, String> departamentos = new LinkedHashMap<>();
        jdbc.sql("SELECT codigo, nombre FROM par_departamentos").query((rs, n) -> departamentos.put(rs.getString(1), rs.getString(2))).list();
        Map<String, Municipio> municipios = new HashMap<>();
        jdbc.sql("SELECT codigo, departamento, nombre FROM par_municipios")
                .query((rs, n) -> municipios.put(rs.getString(1), new Municipio(rs.getString(2), rs.getString(3)))).list();
        List<String> modalidades = jdbc.sql("SELECT nombre FROM par_modalidades ORDER BY orden, nombre").query(String.class).list();
        Map<String, Boolean> fuerzas = new LinkedHashMap<>();
        jdbc.sql("SELECT codigo, sanidad IS NOT NULL AND codigo <> 'NA' FROM par_fuerzas WHERE activo ORDER BY orden")
                .query((rs, n) -> fuerzas.put(rs.getString(1), rs.getBoolean(2))).list();
        Map<Long, String> regionales = new LinkedHashMap<>();
        jdbc.sql("SELECT id, nombre FROM par_regionales WHERE activo").query((rs, n) -> regionales.put(rs.getLong(1), rs.getString(2))).list();
        Map<Integer, String> perfiles = new LinkedHashMap<>();
        jdbc.sql("SELECT id, nombre FROM perfiles WHERE activo ORDER BY id").query((rs, n) -> perfiles.put(rs.getInt(1), rs.getString(2))).list();
        return new Catalogos(departamentos, municipios, modalidades, fuerzas, regionales, perfiles);
    }

    private Existentes existentes() {
        List<PuntoExistente> puntos = jdbc.sql("SELECT id, sigla, nombre, unidad, fuerza, modalidad, activo FROM puntos ORDER BY id")
                .query((rs, n) -> new PuntoExistente(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getBoolean(7))).list();
        List<UsuarioExistente> lista = usuarios.todos().stream().map(u -> new UsuarioExistente(u.id(), u.usuario(), u.correo(), u.perfiles(),
                u.activo(), u.alcanceFuerza(), u.alcancePunto(), u.alcanceRegional())).toList();
        return new Existentes(puntos, lista);
    }

    // ---------- Apoyo ----------

    private static void cambio(List<String> campos, Map<String, Object> antes, Map<String, Object> despues, String campo, Object a, Object b) {
        if (!igual(a, b)) {
            campos.add(campo);
            antes.put(campo, a);
            despues.put(campo, b);
        }
    }

    /** Documento y teléfono: se comparan los valores reales y a la bitácora van enmascarados (Ley 1581). */
    private static void cambioEnmascarado(List<String> campos, Map<String, Object> antes, Map<String, Object> despues, String campo, String a,
                                          String b) {
        if (!igual(a, b)) {
            campos.add(campo);
            antes.put(campo, ImportadorMaestros.enmascarar(a));
            despues.put(campo, ImportadorMaestros.enmascarar(b));
        }
    }

    private static boolean igual(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.longValue() == y.longValue();
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            return new LinkedHashSet<>(x).equals(new LinkedHashSet<>(y));
        }
        return Objects.equals(a == null ? null : a.toString().strip(), b == null ? null : b.toString().strip());
    }

    @SuppressWarnings("unchecked")
    private static List<String> correos(Object v) {
        return v == null ? List.of() : (List<String>) v;
    }

    private static List<String> arreglo(Object v) {
        try {
            return v instanceof Array a ? List.of((String[]) a.getArray()) : v instanceof String[] s ? List.of(s) : List.of();
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    private static String valor(Map<String, Object> d, String campo, String actual) {
        return d.get(campo) instanceof String s && !s.isBlank() ? s : actual;
    }

    private static List<String> nombres(Collection<Perfil> perfiles) {
        return perfiles.stream().sorted().map(Perfil::nombre).toList();
    }
}
