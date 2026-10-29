package co.com.agsamericas.digsa.analitica.homologacion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.SugeridorHomologacion;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que recibe el proveedor de IA de la revisión (analitica-homologacion, «Qué recibe la IA»; RESP L5721, Ley 1581): solo el texto del
 * medicamento y los candidatos del CUM (número y descripción del INVIMA); nunca seudónimos, ESM ni cantidades. Un CUM inventado se descarta
 * y una falla del proveedor da «Sin sugerencia automática» sin error. Proveedor simulado que captura los argumentos.
 */
class SugeridorHomologacionTest {

    /** Proveedor simulado: guarda lo que recibe y responde lo que se le diga (o lanza). */
    private static final class Simulado implements SugeridorHomologacion {
        final List<String> textos = new ArrayList<>();
        final List<List<Candidato>> candidatos = new ArrayList<>();
        Optional<Sugerencia> respuesta = Optional.empty();
        RuntimeException falla;

        @Override
        public boolean disponible() {
            return true;
        }

        @Override
        public String proveedor() {
            return "simulado";
        }

        @Override
        public Optional<Sugerencia> sugerir(String textoOriginal, List<Candidato> c) {
            textos.add(textoOriginal);
            candidatos.add(c);
            if (falla != null) {
                throw falla;
            }
            return respuesta;
        }
    }

    /** Candidato como lo arma la comparación (con campos que NO deben salir hacia el proveedor). */
    private static Map<String, Object> candidato(String cum) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cum", cum);
        m.put("conjunto", "vigentes");
        m.put("producto", "LOSARTAN 50 MG (FICTICIO)");
        m.put("forma", "TABLETA RECUBIERTA");
        m.put("estadoCum", "Activo");
        m.put("registro", "INVIMA 2020M-0000001");
        m.put("atc", "C09CA01");
        m.put("principios", "LOSARTAN POTASICO 50 mg");
        return m;
    }

    @Test
    @DisplayName("solo nombres: el proveedor recibe el texto del medicamento y CUM + descripción del INVIMA; la sugerencia válida se muestra")
    void soloNombres() {
        Simulado s = new Simulado();
        s.respuesta = Optional.of(new SugeridorHomologacion.Sugerencia("20010037-1", "misma DCI y forma", new BigDecimal("0.9")));
        Map<String, Object> r = ServicioRevision.sugerir(s, "LOSARTAN 50 MG CAP", List.of(candidato("20010037-1"), candidato("20010074-2")));
        assertThat(s.textos).containsExactly("LOSARTAN 50 MG CAP");
        List<SugeridorHomologacion.Candidato> enviados = s.candidatos.getFirst();
        assertThat(enviados).extracting(SugeridorHomologacion.Candidato::cum).containsExactly("20010037-1", "20010074-2");
        assertThat(enviados.getFirst().descripcion()).isEqualTo("LOSARTAN 50 MG (FICTICIO) · LOSARTAN POTASICO 50 mg · TABLETA RECUBIERTA");
        // Nada del registro de dispensación ni del registro sanitario: solo número y descripción
        assertThat(SugeridorHomologacion.Candidato.class.getRecordComponents()).hasSize(2);
        assertThat(enviados.toString()).doesNotContain("INVIMA 2020M").doesNotContain("vigentes");
        assertThat(r.get("mensaje")).isEqualTo("Sugerencia (IA)");
        assertThat(r.get("sugerencia").toString()).contains("20010037-1");
    }

    @Test
    @DisplayName("CUM inventado o falla del proveedor: «Sin sugerencia automática», sin error y sin sugerencia")
    void inventadoOFalla() {
        Simulado s = new Simulado();
        s.respuesta = Optional.of(new SugeridorHomologacion.Sugerencia("99999999-9", "inventado", BigDecimal.ONE));
        Map<String, Object> r = ServicioRevision.sugerir(s, "LOSARTAN", List.of(candidato("20010037-1")));
        assertThat(r.get("mensaje")).isEqualTo("Sin sugerencia automática");
        assertThat(r.get("sugerencia")).isNull();
        s.falla = new IllegalStateException("el proveedor repitió el texto LOSARTAN 50 MG CAP");
        Map<String, Object> f = ServicioRevision.sugerir(s, "LOSARTAN", List.of(candidato("20010037-1")));
        assertThat(f.get("mensaje")).isEqualTo("Sin sugerencia automática");
        assertThat(f.get("disponible")).isEqualTo(true);
    }

    @Test
    @DisplayName("sin proveedor: no se llama y responde «Sin sugerencia automática»")
    void sinProveedor() {
        Map<String, Object> r = ServicioRevision.sugerir(new SugeridorNoDisponible(), "LOSARTAN", List.of(candidato("20010037-1")));
        assertThat(r.get("disponible")).isEqualTo(false);
        assertThat(r.get("mensaje")).isEqualTo("Sin sugerencia automática");
    }
}
