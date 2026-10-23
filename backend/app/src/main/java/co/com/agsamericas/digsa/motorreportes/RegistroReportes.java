package co.com.agsamericas.digsa.motorreportes;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registro de los reportes: descubre al arrancar todos los beans {@link ProveedorReporte} de los módulos. Un id repetido o que
 * choca con una ruta del motor impide el arranque y el log dice cuáles chocan.
 */
@Component
public class RegistroReportes {

    private static final Logger LOG = LoggerFactory.getLogger(RegistroReportes.class);
    /** Rutas propias del motor bajo /api/reportes: ningún reporte puede llamarse así. */
    static final Set<String> RUTAS = Set.of("catalogo", "presets", "descargas", "verificar", "opciones");

    private final Map<String, ProveedorReporte> porId;

    public RegistroReportes(List<ProveedorReporte> proveedores) {
        Map<String, List<String>> repetidos = proveedores.stream().collect(Collectors.groupingBy(p -> p.definicion().id(),
                Collectors.mapping(p -> p.getClass().getName(), Collectors.toList())));
        repetidos.forEach((id, clases) -> {
            if (clases.size() > 1) {
                throw new IllegalStateException("Hay " + clases.size() + " reportes con el id «" + id + "»: " + String.join(", ", clases));
            }
            if (RUTAS.contains(id)) {
                throw new IllegalStateException("El id de reporte «" + id + "» choca con una ruta del motor (" + clases.get(0) + ")");
            }
        });
        Map<String, ProveedorReporte> m = new LinkedHashMap<>();
        proveedores.stream().sorted(Comparator.comparing((ProveedorReporte p) -> p.definicion().grupo())
                .thenComparingInt(p -> p.definicion().posicion()).thenComparing(p -> p.definicion().id())).forEach(p -> m.put(p.definicion().id(), p));
        this.porId = m;
        LOG.info("[reportes] {} reportes registrados: {}", m.size(), String.join(", ", m.keySet()));
    }

    public Optional<ProveedorReporte> buscar(String id) {
        return Optional.ofNullable(id == null ? null : porId.get(id));
    }

    /** Todos, en el orden del catálogo: grupo, posición e id. */
    public List<ProveedorReporte> todos() {
        return List.copyOf(porId.values());
    }
}
