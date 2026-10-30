package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Membresía por lotes (A7.1, AN.32): la memoria no crece con la membresía; el COPY lee el archivo y el resto del búfer en orden. */
class VolcadoTest {

    private static String leer(Reader r) throws IOException {
        StringWriter w = new StringWriter();
        r.transferTo(w);
        return w.toString();
    }

    @Test
    @DisplayName("con lotes de 3 líneas, 7 líneas quedan 6 en el archivo y 1 en memoria; el lector las da todas en orden y cerrar borra el archivo")
    void porLotes() throws IOException {
        StringBuilder esperado = new StringBuilder();
        try (Volcado v = new Volcado(3)) {
            for (int i = 1; i <= 7; i++) {
                v.linea().append(i).append("\tfila\n");
                v.fin();
                esperado.append(i).append("\tfila\n");
            }
            assertThat(v.enArchivo()).isTrue();
            assertThat(v.linea().length()).as("en memoria solo el lote abierto").isEqualTo("7\tfila\n".length());
            assertThat(v.lineas()).isEqualTo(7);
            try (Reader r = v.lector()) {
                assertThat(leer(r)).isEqualTo(esperado.toString());
            }
        }
        try (Volcado chico = new Volcado()) {
            chico.linea().append("1\n");
            chico.fin();
            assertThat(chico.enArchivo()).isFalse();
            assertThat(leer(chico.lector())).isEqualTo("1\n");
        }
    }
}
