package co.com.agsamericas.digsa.plataforma;

import java.util.List;

/**
 * Puerto de correo saliente. Adaptadores: SMTP (corporativo) y archivo (solo en local). ACS Email (decisión del 2026-10-20) entra
 * como otro adaptador sin cambiar a quien envía.
 */
public interface Correo {

    record Adjunto(String nombre, String tipo, byte[] contenido) {
    }

    record Mensaje(List<String> para, List<String> copia, String asunto, String html, List<Adjunto> adjuntos) {
        public Mensaje {
            para = List.copyOf(para);
            copia = copia == null ? List.of() : List.copyOf(copia);
            adjuntos = adjuntos == null ? List.of() : List.copyOf(adjuntos);
        }

        public static Mensaje simple(String para, String asunto, String html) {
            return new Mensaje(List.of(para), List.of(), asunto, html, List.of());
        }
    }

    /** Resultado del envío: si salió, a quién se entregó y quién lo rechazó; el error sin datos internos. */
    record Resultado(boolean ok, List<String> entregados, List<String> rechazados, String error) {
        public static Resultado exito(List<String> todos) {
            return new Resultado(true, List.copyOf(todos), List.of(), null);
        }

        public static Resultado falla(List<String> todos, String error) {
            return new Resultado(false, List.of(), List.copyOf(todos), error);
        }
    }

    /** Envía y devuelve el resultado; nunca lanza por una falla del servidor de correo. */
    Resultado enviar(Mensaje mensaje);

    /** ¿Es un envío real? (en local los correos quedan en archivo y no salen). */
    boolean real();
}
