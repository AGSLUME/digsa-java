package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.agenda.RepositorioVisitas;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.ValidadorArchivos;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Evidencias de la visita (Anexo 5, prueba 12: varios archivos en una sola petición, todos o ninguno). Referencia:
 * docs/especificaciones/archivos.
 */
@Service
public class ServicioArchivos {

    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public record Archivo(long id, long visitaId, String clave, String nombre, String ruta, String tipo, long tamano, String sha256) {
    }

    private final ServicioFormulario formulario;
    private final ValidadorArchivos validador;
    private final Almacen almacen;
    private final RepositorioVisitas visitas;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock reloj;

    public ServicioArchivos(ServicioFormulario formulario, ValidadorArchivos validador, Almacen almacen, RepositorioVisitas visitas, JdbcClient jdbc,
                            TransactionTemplate tx, Clock reloj) {
        this.formulario = formulario;
        this.validador = validador;
        this.almacen = almacen;
        this.visitas = visitas;
        this.jdbc = jdbc;
        this.tx = tx;
        this.reloj = reloj;
    }

    /** Sube uno o varios archivos a la misma clave («visita» o «it:CODIGO»). Si uno falla, no queda ninguno. */
    public List<Archivo> subir(long visitaId, String clave, List<MultipartFile> archivos, UsuarioSesion quien, Set<Perfil> perfiles) {
        if (archivos == null || archivos.isEmpty()) {
            throw ErrorNegocio.validacion("Elija al menos un archivo.");
        }
        if (archivos.size() > ValidadorArchivos.MAX_POR_PETICION) {
            throw ErrorNegocio.validacion("Máximo " + ValidadorArchivos.MAX_POR_PETICION + " archivos por envío.");
        }
        autorizarClave(visitaId, clave, quien, perfiles, false);
        // Primero se validan todos (por el contenido); si uno no sirve, no se escribe nada
        List<ValidadorArchivos.Valido> validos = new ArrayList<>();
        List<String> nombres = new ArrayList<>();
        for (MultipartFile f : archivos) {
            String nombre = nombre(f.getOriginalFilename());
            // Tope antes de leer el archivo a memoria (el de video es el mayor; el exacto se aplica al saber el tipo)
            if (f.getSize() > ValidadorArchivos.MAX_VIDEO) {
                throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo «" + nombre + "» pesa más de " + ValidadorArchivos.MAX_VIDEO / (1024 * 1024) + " MB.");
            }
            try {
                validos.add(validador.validar(nombre, f.getBytes()));
            } catch (IOException e) {
                throw ErrorNegocio.validacion("No se pudo leer el archivo «" + nombre + "».");
            }
            nombres.add(nombre);
        }
        List<String> escritas = new ArrayList<>();
        try {
            List<String> rutas = new ArrayList<>();
            for (ValidadorArchivos.Valido v : validos) {
                String ruta = "visita_" + visitaId + "/" + MARCA.format(OffsetDateTime.now(reloj).atZoneSameInstant(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA))
                        + "_" + Huellas.secreto(8) + "." + v.extension();
                almacen.guardar(ruta, v.contenido());
                escritas.add(ruta);
                rutas.add(ruta);
            }
            return tx.execute(t -> {
                // Se vuelve a comprobar con la visita bloqueada: pudo cerrarse mientras se escribían los archivos
                autorizarClave(visitaId, clave, quien, perfiles, true);
                List<Archivo> r = new ArrayList<>();
                for (int i = 0; i < validos.size(); i++) {
                    ValidadorArchivos.Valido v = validos.get(i);
                    long id = jdbc.sql("""
                            INSERT INTO archivos (visita_id, clave, nombre_original, ruta, tipo, tamano, sha256, subido_por, subido)
                            VALUES (:v, :c, :n, :r, :t, :tam, :s, :u, :ahora) RETURNING id""")
                            .param("v", visitaId).param("c", clave).param("n", nombres.get(i)).param("r", rutas.get(i)).param("t", v.tipo())
                            .param("tam", (long) v.contenido().length).param("s", v.sha256()).param("u", quien.id())
                            .param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
                    r.add(new Archivo(id, visitaId, clave, nombres.get(i), rutas.get(i), v.tipo(), v.contenido().length, v.sha256()));
                }
                visitas.historial(visitaId, quien.id(), quien.usuario(), "Soportes cargados", clave + ": " + r.size() + " archivo(s)");
                return r;
            });
        } catch (IOException e) {
            escritas.forEach(almacen::descartar);
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudieron guardar los archivos. No quedó ninguno; intente de nuevo.");
        } catch (RuntimeException e) {
            escritas.forEach(almacen::descartar);
            throw e;
        }
    }

    /** Quitar un soporte lo marca eliminado (no se borra del almacén) y solo mientras la visita admita cambios. */
    public void quitar(long archivoId, UsuarioSesion quien, Set<Perfil> perfiles) {
        tx.executeWithoutResult(t -> {
            Archivo a = porId(archivoId).orElseThrow(() -> ErrorNegocio.noEncontrado("El archivo no existe."));
            if ("acta".equals(a.clave())) {
                throw ErrorNegocio.validacion("El acta no se quita.");
            }
            autorizarClave(a.visitaId(), a.clave(), quien, perfiles, true);
            jdbc.sql("UPDATE archivos SET eliminado = :ahora WHERE id = :id AND eliminado IS NULL").param("ahora", OffsetDateTime.now(reloj))
                    .param("id", archivoId).update();
            visitas.historial(a.visitaId(), quien.id(), quien.usuario(), "Soporte quitado", a.clave() + ": " + a.nombre());
        });
    }

    /** Para descargar: solo quien tiene acceso a la visita (equipo o coordinador). */
    public Archivo paraDescargar(long archivoId, UsuarioSesion quien, Set<Perfil> perfiles) {
        Archivo a = porId(archivoId).orElseThrow(() -> ErrorNegocio.noEncontrado("El archivo no existe."));
        formulario.acceso(a.visitaId(), quien, perfiles, false); // lanza 404 si no es del equipo ni coordinador
        return a;
    }

    public InputStream abrir(Archivo a) throws IOException {
        return almacen.leer(a.ruta());
    }

    java.util.Optional<Archivo> porId(long id) {
        return jdbc.sql("SELECT id, visita_id, clave, nombre_original, ruta, tipo, tamano, sha256 FROM archivos WHERE id = :id AND eliminado IS NULL")
                .param("id", id).query((rs, n) -> new Archivo(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getLong(7), rs.getString(8))).optional();
    }

    /** Guarda un contenido generado en el servidor (el PDF del acta) con las mismas reglas que un archivo subido. */
    Archivo guardarGenerado(long visitaId, String clave, String nombre, byte[] contenido, UsuarioSesion quien) {
        ValidadorArchivos.Valido v = validador.validar(nombre, contenido);
        String ruta = "visita_" + visitaId + "/actas/" + MARCA.format(OffsetDateTime.now(reloj).atZoneSameInstant(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA))
                + "_" + Huellas.secreto(8) + "." + v.extension();
        try {
            almacen.guardar(ruta, v.contenido());
        } catch (IOException e) {
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar el acta en el almacén de archivos.");
        }
        try {
            long id = jdbc.sql("""
                    INSERT INTO archivos (visita_id, clave, nombre_original, ruta, tipo, tamano, sha256, subido_por, subido)
                    VALUES (:v, :c, :n, :r, :t, :tam, :s, :u, :ahora) RETURNING id""")
                    .param("v", visitaId).param("c", clave).param("n", nombre).param("r", ruta).param("t", v.tipo())
                    .param("tam", (long) v.contenido().length).param("s", v.sha256()).param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj))
                    .query(Long.class).single();
            return new Archivo(id, visitaId, clave, nombre, ruta, v.tipo(), v.contenido().length, v.sha256());
        } catch (RuntimeException e) {
            almacen.descartar(ruta);
            throw e;
        }
    }

    private void autorizarClave(long visitaId, String clave, UsuarioSesion quien, Set<Perfil> perfiles, boolean bloquear) {
        Acceso a = formulario.acceso(visitaId, quien, perfiles, bloquear);
        if (!a.escribible()) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, a.motivoSoloLectura());
        }
        if ("visita".equals(clave)) {
            if (!a.escribeVisita()) {
                throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Los soportes generales de la visita los carga el auditor líder.");
            }
            return;
        }
        if (clave == null || !clave.matches("^it:[A-Z0-9][A-Z0-9-]{1,15}$")) {
            throw ErrorNegocio.validacion("El soporte debe ser de la visita o de un ítem del checklist.");
        }
        String codigo = clave.substring(3);
        ServicioFormulario.ItemVisita item = formulario.items(a.visita()).stream().filter(i -> i.codigo().equals(codigo)).findFirst()
                .orElseThrow(() -> ErrorNegocio.validacion("El ítem " + codigo + " no es de esta visita."));
        if (!a.escribeParte(item.parte())) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "La parte " + item.parte() + " es de otro auditor.");
        }
    }

    static String nombre(String original) {
        String n = original == null ? "archivo" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|*?:]", "_").strip();
        if (n.isEmpty()) {
            n = "archivo";
        }
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    static Map<String, String> encabezados(Archivo a) {
        boolean enLinea = a.tipo().startsWith("image/") || a.tipo().startsWith("video/") || "application/pdf".equals(a.tipo());
        // El nombre se descarga con la extensión del tipo detectado, no con la que trajo el archivo (p. ej. un .lnk que es texto)
        String extension = a.ruta().substring(a.ruta().lastIndexOf('.') + 1);
        String base = a.nombre().contains(".") ? a.nombre().substring(0, a.nombre().lastIndexOf('.')) : a.nombre();
        String nombre = (base.isBlank() ? "archivo" : base) + "." + extension;
        String ascii = nombre.replaceAll("[^A-Za-z0-9._-]", "_");
        String utf8 = java.net.URLEncoder.encode(nombre, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return Map.of("Content-Disposition", (enLinea ? "inline" : "attachment") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8,
                "X-Content-Type-Options", "nosniff", "Cache-Control", "no-store", // con datos personales: no quedan en la caché de un equipo compartido
                "Content-Security-Policy", "application/pdf".equals(a.tipo()) ? "" : "sandbox; default-src 'none'");
    }
}
