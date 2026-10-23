package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.Opcion;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Caminos genéricos del motor que los reportes de visitas no usan: texto, sin periodo, topes, límite y parámetros que chocan. */
class MotorReportesTest {

    /** Transacciones de mentira: registra con qué aislamiento y propagación se abrió la lectura. */
    private static final class Transacciones extends AbstractPlatformTransactionManager {
        final List<TransactionDefinition> abiertas = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            abiertas.add(definition);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    /** Proveedor de prueba: sin periodo, con búsqueda por texto y una lista; entrega {@code n} filas e ignora el LIMIT a propósito. */
    private static final class Falso implements ProveedorReporte {
        final DefinicionReporte d;
        int n = 3;
        int ancho = 1;
        ConsultaReporte recibida;

        Falso(DefinicionReporte d) {
            this.d = d;
        }

        @Override
        public DefinicionReporte definicion() {
            return d;
        }

        @Override
        public List<Opcion> opciones(String filtro, Alcance alcance) {
            return List.of(new Opcion("A", "Opción A"), new Opcion("B", "Opción B"));
        }

        @Override
        public void filas(ConsultaReporte c, Consumer<List<Object>> destino) {
            recibida = c;
            for (int i = 0; i < n; i++) {
                List<Object> fila = new ArrayList<>();
                for (int j = 0; j < ancho; j++) {
                    fila.add(i);
                }
                destino.accept(fila);
            }
        }
    }

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-22T15:00:00Z"), ZoneOffset.UTC);
    private static final Set<Perfil> ADMIN = Set.of(Perfil.ADMINISTRADOR);

    private final Transacciones tx = new Transacciones();

    private MotorReportes motor(ProveedorReporte p, int filasPantalla, String inicioContrato) {
        ServicioCatalogos catalogos = new ServicioCatalogos(null, null, RELOJ) {
            @Override
            public String textoParametro(String clave, String defecto) {
                return "inicio_contrato".equals(clave) ? inicioContrato : defecto;
            }
        };
        return new MotorReportes(new RegistroReportes(List.of(p)), new ConfiguracionReportes(filasPantalla), catalogos, RELOJ, tx, null);
    }

    private static Falso sinPeriodo(int maxFilas) {
        return new Falso(DefinicionReporte.nuevo("prueba_motor", "Prueba del motor").grupo(GrupoReporte.GLOSAS).modulo("prueba")
                .pantalla(Pantallas.LOGS).maxFilas(maxFilas).filtro(Filtro.texto("texto", "Texto")).filtro(Filtro.lista("tipo", "Tipo"))
                .columna("Valor", TipoColumna.ENTERO).crear());
    }

    private static Falso conPeriodo() {
        return new Falso(DefinicionReporte.nuevo("prueba_periodo", "Prueba con periodo").grupo(GrupoReporte.GLOSAS).modulo("prueba")
                .periodo(CampoTemporal.FECHA_HORA, EnumSet.of(Agrupacion.HORA, Agrupacion.MES)).columna("Valor", TipoColumna.ENTERO).crear());
    }

    @Test
    @DisplayName("en pantalla pide una fila más (LIMIT) y avisa el truncado; en archivo, pasar el tope es 422; nunca guarda de más")
    void limitesYTopes() {
        Falso p = sinPeriodo(2);
        MotorReportes m = motor(p, 2, "");
        MotorReportes.Solicitud s = m.resolver("prueba_motor", Map.of(), ADMIN, new Alcance.Todo());
        MotorReportes.Resultado pantalla = m.generar(s, "admin", false);
        assertThat(p.recibida.limite()).isEqualTo(3);
        assertThat(pantalla.truncado()).isTrue();
        assertThat(pantalla.filas()).hasSize(2);
        assertThatThrownBy(() -> m.generar(s, "admin", true)).isInstanceOf(ErrorNegocio.class).hasMessageContaining("pasa de 2 filas");

        p.n = 50; // el proveedor no aplicó el LIMIT: el motor no acumula más de lo pedido
        assertThat(m.generar(s, "admin", false).filas()).hasSize(2);
        p.n = 2;
        MotorReportes.Resultado archivo = m.generar(s, "admin", true);
        assertThat(archivo.truncado()).isFalse();
        assertThat(archivo.filas()).hasSize(2);
        assertThatThrownBy(() -> archivo.filas().add(List.of())).isInstanceOf(UnsupportedOperationException.class);
        // La lectura va en una transacción propia, de solo lectura y en REPEATABLE READ
        TransactionDefinition t = tx.abiertas.get(tx.abiertas.size() - 1);
        assertThat(t.isReadOnly()).isTrue();
        assertThat(t.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(t.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("sin tope de archivo (maxFilas 0) el proveedor no recibe límite; una fila de otro ancho es un error de programación")
    void sinTopeYAncho() {
        Falso p = sinPeriodo(0);
        MotorReportes m = motor(p, 5000, "");
        MotorReportes.Solicitud s = m.resolver("prueba_motor", Map.of(), ADMIN, new Alcance.Todo());
        m.generar(s, "admin", true);
        assertThat(p.recibida.limite()).isZero();
        p.ancho = 2;
        assertThatThrownBy(() -> m.generar(s, "admin", true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("2 columnas");
    }

    @Test
    @DisplayName("filtro de texto: un solo valor de hasta 200 caracteres; el periodo no aplica a un reporte sin periodo")
    void textoYSinPeriodo() {
        MotorReportes m = motor(sinPeriodo(0), 5000, "");
        MotorReportes.Solicitud s = m.resolver("prueba_motor", Map.of("texto", List.of("  clave  "), "tipo", List.of("B", "A", "B")), ADMIN,
                new Alcance.Todo());
        assertThat(s.consulta().valores("texto")).containsExactly("clave");
        assertThat(s.consulta().valores("tipo")).containsExactly("A", "B");
        assertThat(s.filtrosEncabezado()).containsEntry("Tipo", "Opción A, Opción B").doesNotContainKey("Periodo");
        assertThat(s.consulta().desde()).isNull();
        assertThatThrownBy(() -> m.resolver("prueba_motor", Map.of("texto", List.of("x".repeat(201))), ADMIN, new Alcance.Todo()))
                .hasMessageContaining("hasta 200");
        assertThatThrownBy(() -> m.resolver("prueba_motor", Map.of("texto", List.of("a", "b")), ADMIN, new Alcance.Todo()))
                .hasMessageContaining("un solo texto");
        assertThatThrownBy(() -> m.resolver("prueba_motor", Map.of("desde", List.of("2026-01-01")), ADMIN, new Alcance.Todo()))
                .hasMessageContaining("no aplica");
        assertThatThrownBy(() -> m.resolver("prueba_motor", Map.of("otro", List.of("")), ADMIN, new Alcance.Todo())).hasMessageContaining("no aplica");
        assertThatThrownBy(() -> m.resolver("prueba_motor", Map.of("tipo", List.of("Z")), ADMIN, new Alcance.Todo()))
                .hasMessageContaining("no existe o está fuera de su alcance");
        // Pantalla LOGS: el coordinador no lo ve (404 sin revelar que existe)
        assertThatThrownBy(() -> m.resolver("prueba_motor", Map.of(), Set.of(Perfil.COORDINADOR), new Alcance.Todo()))
                .hasMessageContaining("no existe");
        assertThat(m.catalogo(Set.of(Perfil.COORDINADOR), new Alcance.Todo())).isEmpty();
    }

    @Test
    @DisplayName("periodo: preset y fechas a la vez es 422; un valor repetido aunque el primero venga vacío es 422; horas sobre fecha y hora")
    void parametrosDelPeriodo() {
        MotorReportes m = motor(conPeriodo(), 5000, "2026-12-29");
        assertThatThrownBy(() -> m.resolver("prueba_periodo", Map.of("preset", List.of("hoy"), "desde", List.of("2026-01-01")), ADMIN, new Alcance.Todo()))
                .hasMessageContaining("no los dos");
        assertThatThrownBy(() -> m.resolver("prueba_periodo", Map.of("desde", List.of("2026-01-01", "2026-02-01")), ADMIN, new Alcance.Todo()))
                .hasMessageContaining("un solo valor");
        MotorReportes.Solicitud s = m.resolver("prueba_periodo", Map.of("desde", List.of("", "2026-02-01"), "agrupacion", List.of("hora")), ADMIN,
                new Alcance.Todo());
        assertThat(s.periodo()).isEqualTo("2026-02-01 a 2026-10-22");
        assertThat(s.consulta().agrupacion()).isEqualTo(Agrupacion.HORA);
        assertThat(m.presets()).extracting(x -> x.clave()).doesNotContain("anio_contrato");
    }

    @Test
    @DisplayName("un inicio de contrato que no es fecha no rompe los presets: el año de contrato no se ofrece (y queda en el log)")
    void inicioContratoInvalido() {
        assertThat(motor(conPeriodo(), 5000, "29/12/2026").presets()).extracting(x -> x.clave()).doesNotContain("anio_contrato").contains("hoy");
        assertThat(motor(conPeriodo(), 5000, "2025-12-29").presets()).extracting(x -> x.clave()).contains("anio_contrato");
    }
}
