package co.com.agsamericas.digsa.analitica;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Huellas;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Generador sintético del insumo: determinista, con los errores sembrados contados y totales que cuadran entre dimensiones. */
class GeneradorSinteticoTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("semilla fija: dos generaciones dan el mismo SHA-256 del CSV y de los totales")
    void determinista() throws IOException {
        GeneradorSintetico.Resultado a = GeneradorSintetico.generar(dir.resolve("a"), "s", 3000, 42L);
        GeneradorSintetico.Resultado b = GeneradorSintetico.generar(dir.resolve("b"), "s", 3000, 42L);
        assertThat(Huellas.sha256(Files.readAllBytes(a.csv()))).isEqualTo(Huellas.sha256(Files.readAllBytes(b.csv())));
        assertThat(Huellas.sha256(Files.readAllBytes(a.totales()))).isEqualTo(Huellas.sha256(Files.readAllBytes(b.totales())));
        GeneradorSintetico.Resultado c = GeneradorSintetico.generar(dir.resolve("c"), "s", 3000, 43L);
        assertThat(Huellas.sha256(Files.readAllBytes(c.csv()))).isNotEqualTo(Huellas.sha256(Files.readAllBytes(a.csv())));
    }

    @Test
    @DisplayName("errores sembrados: todas las causas, recibidos = procesados + rechazados y el encabezado del insumo")
    void erroresSembrados() throws IOException {
        GeneradorSintetico.Resultado r = GeneradorSintetico.generar(dir, "s", 5000, 7L);
        assertThat(r.rechazosPorCausa().keySet()).containsExactlyInAnyOrderElementsOf(GeneradorSintetico.CAUSAS);
        assertThat(r.recibidos()).isEqualTo(r.procesados() + r.rechazados());
        long filas = Files.lines(r.csv(), StandardCharsets.UTF_8).count() - 1;
        assertThat(filas).isEqualTo(5000);
        assertThat(Files.lines(r.csv(), StandardCharsets.UTF_8).findFirst().orElseThrow()).isEqualTo("﻿" + GeneradorSintetico.ENCABEZADO);
        // una fila con dos causas: la suma por causa supera a los rechazados en uno
        assertThat(r.rechazosPorCausa().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(r.rechazados() + 1);
    }

    @Test
    @DisplayName("los totales por cualquier dimensión suman el total (gasto, unidades y dispensaciones)")
    void cuadreEntreDimensiones() throws IOException {
        GeneradorSintetico.Resultado r = GeneradorSintetico.generar(dir, "s", 4000, 9L);
        Map<String, Map<String, String[]>> dims = GeneradorSintetico.leerDimensiones(r.totales());
        assertThat(dims.keySet()).containsExactlyInAnyOrder("fuerza", "esm", "modalidad", "atc1", "mes", "cum", "cie10");
        for (var d : dims.entrySet()) {
            BigDecimal gasto = BigDecimal.ZERO;
            long disp = 0;
            long unidades = 0;
            for (String[] v : d.getValue().values()) {
                disp += Long.parseLong(v[0]);
                gasto = gasto.add(new BigDecimal(v[1]));
                unidades += Long.parseLong(v[3]);
            }
            assertThat(gasto).as(d.getKey()).isEqualByComparingTo(r.gasto());
            assertThat(disp).as(d.getKey()).isEqualTo(r.procesados());
            assertThat(unidades).as(d.getKey()).isEqualTo(r.unidades());
        }
        assertThat(dims.get("mes")).hasSize(12);
        assertThat(List.of(GeneradorSintetico.esmConocidos().split(","))).hasSize(42);
    }
}
