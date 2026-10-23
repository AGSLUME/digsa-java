package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.ClaseReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Columna;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contrato ProveedorReporte: lo que impide el arranque y el orden del catálogo. */
class ContratoReportesTest {

    private static DefinicionReporte.Constructor base(String id) {
        return DefinicionReporte.nuevo(id, "Reporte " + id).grupo(GrupoReporte.VISITAS).modulo("prueba").columna("Valor", TipoColumna.ENTERO);
    }

    private static ProveedorReporte proveedor(DefinicionReporte d) {
        return new ProveedorReporte() {
            @Override
            public DefinicionReporte definicion() {
                return d;
            }

            @Override
            public void filas(ConsultaReporte consulta, Consumer<List<Object>> destino) {
            }
        };
    }

    @Test
    @DisplayName("id fuera del patrón, filtro reservado o repetido, GENERAL con dato personal y horas sin hora impiden el arranque")
    void validaciones() {
        assertThatThrownBy(() -> base("Glosas").crear()).hasMessageContaining("no cumple");
        assertThatThrownBy(() -> base("ab").crear()).hasMessageContaining("no cumple");
        assertThatThrownBy(() -> base("glosas").filtro(Filtro.lista("desde", "Desde")).crear()).hasMessageContaining("reservado");
        assertThatThrownBy(() -> base("glosas").filtro(Filtro.lista("fuerza", "Fuerza")).filtro(Filtro.lista("fuerza", "Otra")).crear())
                .hasMessageContaining("repetido");
        assertThatThrownBy(() -> base("glosas").columna(Columna.personal("Paciente", Set.of(Perfil.COORDINADOR))).crear()).hasMessageContaining("Ley 1581");
        assertThatThrownBy(() -> base("glosas").periodo(CampoTemporal.FECHA, EnumSet.of(Agrupacion.HORA)).crear()).hasMessageContaining("horas");
        assertThatThrownBy(() -> base("glosas").periodo(CampoTemporal.NINGUNO, EnumSet.of(Agrupacion.MES)).crear()).hasMessageContaining("sin campo temporal");
        assertThatThrownBy(() -> DefinicionReporte.nuevo("glosas", "Sin columnas").grupo(GrupoReporte.GLOSAS).modulo("x").crear())
                .hasMessageContaining("no tiene columnas");
        assertThatThrownBy(() -> base("glosas").filtro(Filtro.lista("f1", "Fuerza")).filtro(Filtro.lista("f2", "Fuerza")).crear())
                .hasMessageContaining("etiqueta «Fuerza»");
        assertThatThrownBy(() -> base("glosas").filtro(Filtro.lista("f1", "Fuente")).crear()).hasMessageContaining("encabezado");
        assertThatThrownBy(() -> base("glosas").columna("Periodo", TipoColumna.TEXTO).crear()).hasMessageContaining("la pone el motor");
        assertThatThrownBy(() -> base("glosas").columna("Valor", TipoColumna.TEXTO).crear()).hasMessageContaining("repetida");
        assertThatThrownBy(() -> base("glosas").periodo(CampoTemporal.FECHA, Set.of()).maxDias(0).crear()).hasMessageContaining("tope");
        assertThat(base("glosas").fuente(null).crear().fuente()).isEqualTo("Suite DIGSA");
        assertThat(base("glosas").clase(ClaseReporte.OPERATIVO).columna(Columna.personal("Paciente", Set.of(Perfil.COORDINADOR))).crear().columnas())
                .hasSize(2);
    }

    @Test
    @DisplayName("la columna personal solo la ven sus perfiles y el administrador")
    void columnasPorPerfil() {
        DefinicionReporte d = base("glosas").clase(ClaseReporte.OPERATIVO).columna(Columna.personal("Auditor", Set.of(Perfil.COORDINADOR))).crear();
        assertThat(d.columnasPara(Set.of(Perfil.DIGSA))).extracting(Columna::nombre).containsExactly("Valor");
        assertThat(d.columnasPara(Set.of(Perfil.COORDINADOR))).extracting(Columna::nombre).containsExactly("Valor", "Auditor");
        assertThat(d.columnasPara(Set.of(Perfil.ADMINISTRADOR))).extracting(Columna::nombre).containsExactly("Valor", "Auditor");
    }

    @Test
    @DisplayName("registro: un id repetido o que choca con una ruta del motor no arranca; el catálogo va por grupo y posición")
    void registro() {
        assertThatThrownBy(() -> new RegistroReportes(List.of(proveedor(base("glosas").crear()), proveedor(base("glosas").crear()))))
                .hasMessageContaining("2 reportes con el id «glosas»").hasMessageContaining(ContratoReportesTest.class.getName());
        assertThatThrownBy(() -> new RegistroReportes(List.of(proveedor(base("catalogo").crear())))).hasMessageContaining("ruta del motor");
        RegistroReportes r = new RegistroReportes(List.of(proveedor(base("zeta").grupo(GrupoReporte.SEGURIDAD).crear()),
                proveedor(base("beta").posicion(2).crear()), proveedor(base("alfa").posicion(1).crear()),
                proveedor(base("gamma").grupo(GrupoReporte.GLOSAS).crear())));
        assertThat(r.todos()).extracting(p -> p.definicion().id()).containsExactly("gamma", "alfa", "beta", "zeta");
        assertThat(r.buscar("alfa")).isPresent();
        assertThat(r.buscar("otro")).isEmpty();
        assertThat(r.buscar(null)).isEmpty();
    }
}
