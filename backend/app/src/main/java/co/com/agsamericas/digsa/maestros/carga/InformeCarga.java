package co.com.agsamericas.digsa.maestros.carga;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

/**
 * Informe de una corrida de la carga: una línea por fila leída (archivo, hoja, fila, clave natural, acción, campos que cambian y
 * motivo) que se escribe en la carpeta como {@code resultado_carga_<aaaammdd_hhmm>.csv} (UTF-8 con BOM, «;»). En la consola y en el
 * log solo van los conteos y, por cada error, archivo, fila, campo y motivo, SIN valores (Ley 1581): la clave natural (correo,
 * sigla) solo va al CSV.
 */
public class InformeCarga {

    public enum Accion {
        NUEVO("nuevo"), ACTUALIZAR("actualizar"), SIN_CAMBIOS("sin cambios"), ERROR("error"), CORREO_NO_ENVIADO("correo no enviado"),
        AVISO("aviso");

        private final String texto;

        Accion(String texto) {
            this.texto = texto;
        }

        public String texto() {
            return texto;
        }
    }

    /** {@code motivo} nunca lleva valores de las celdas; {@code clave} sí (solo va al CSV). */
    public record Linea(String archivo, String hoja, Integer fila, String clave, Accion accion, String campos, String motivo) {
    }

    private static final DateTimeFormatter NOMBRE = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private final String modo;
    private final List<Linea> lineas = new ArrayList<>();
    private int codigo;
    private boolean escrito;

    public InformeCarga(String modo) {
        this.modo = modo;
    }

    public void agregar(Linea l) {
        lineas.add(l);
    }

    public List<Linea> lineas() {
        return List.copyOf(lineas);
    }

    public long cuenta(Accion a) {
        return lineas.stream().filter(l -> l.accion() == a).count();
    }

    public int codigo() {
        return codigo;
    }

    public void codigo(int c) {
        this.codigo = c;
    }

    public String modo() {
        return modo;
    }

    /** Si la base quedó escrita (aplicar sin errores). */
    public boolean escrito() {
        return escrito;
    }

    void escrito(boolean e) {
        this.escrito = e;
    }

    public String resumen() {
        return "nuevos " + cuenta(Accion.NUEVO) + ", actualizados " + cuenta(Accion.ACTUALIZAR) + ", sin cambios " + cuenta(Accion.SIN_CAMBIOS)
                + ", errores " + cuenta(Accion.ERROR) + ", correos no enviados " + cuenta(Accion.CORREO_NO_ENVIADO);
    }

    /** Escribe el CSV en la carpeta. @return la ruta escrita */
    public Path escribir(Path carpeta, Clock reloj) throws IOException {
        Files.createDirectories(carpeta);
        String base = LocalDateTime.now(reloj).format(NOMBRE);
        Path p = carpeta.resolve(LectorPlantillas.PREFIJO_INFORME + base + ".csv");
        for (int i = 2; Files.exists(p); i++) {
            p = carpeta.resolve(LectorPlantillas.PREFIJO_INFORME + base + "_" + i + ".csv");
        }
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("archivo;hoja;fila;clave;accion;campos;motivo\r\n");
        for (Linea l : lineas) {
            sb.append(celda(l.archivo())).append(';').append(celda(l.hoja())).append(';').append(l.fila() == null ? "" : l.fila()).append(';')
                    .append(celda(l.clave())).append(';').append(l.accion().texto()).append(';').append(celda(l.campos())).append(';')
                    .append(celda(l.motivo())).append("\r\n");
        }
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        return p;
    }

    /** Conteos y errores sin valores. */
    public void registrar(Logger log) {
        log.info("Carga de maestros ({}): {}.", modo, resumen());
        for (Linea l : lineas) {
            String donde = l.hoja() != null ? l.hoja() : l.archivo();
            String lugar = donde == null ? "carga" : donde + (l.fila() == null ? "" : " fila " + l.fila());
            switch (l.accion()) {
                case ERROR -> log.error("{} · {}", lugar, l.motivo());
                case CORREO_NO_ENVIADO -> log.warn("{} · correo de bienvenida no enviado: {}", lugar, l.motivo());
                case AVISO -> log.warn("{}", l.motivo());
                default -> {
                }
            }
        }
    }

    /** Celda del CSV: comillas si hace falta y sin fórmulas (una celda que empieza por = + - @ se abre como texto). */
    static String celda(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        String s = v;
        if ("=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
