package co.com.agsamericas.digsa.plataforma;

import java.util.List;
import java.util.Objects;

/**
 * Encabezado de un reporte (Anexo 5, 2.9): título y líneas «etiqueta: valor» en orden (periodo, generado, usuario, establecimiento,
 * filtros, fuente, registros, código de verificación y huella de los datos). La página la pone cada formato: «Página N de M» en PDF
 * (cada hoja) y en el pie de impresión de Excel; «1 de 1 (archivo continuo)» en CSV y TXT.
 */
public record ReporteEncabezado(String titulo, List<Linea> lineas, String codigo) {

    public record Linea(String etiqueta, String valor) {

        public Linea {
            Objects.requireNonNull(etiqueta);
            valor = valor == null ? "" : valor;
        }
    }

    public ReporteEncabezado {
        Objects.requireNonNull(titulo);
        lineas = List.copyOf(lineas);
        codigo = codigo == null ? "" : codigo;
    }
}
