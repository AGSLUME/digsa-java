package co.com.agsamericas.digsa.analitica.homologacion;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * «Sin acceso a la red» (analitica-homologacion, «Carga de una versión por archivo»): la homologación y la carga de referencias solo
 * leen la base y los archivos subidos; ninguna clase del paquete abre conexiones HTTP o sockets (la descarga de datos.gov.co es
 * herramientas/descargar_referencias.py, fuera del sistema). La sugerencia de la IA entra por el puerto SugeridorHomologacion, que vive en
 * analitica y recibe solo textos de medicamentos.
 */
class HomologacionSinRedTest {

    private static final JavaClasses CLASES = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("co.com.agsamericas.digsa.analitica.homologacion");

    @Test
    @DisplayName("el paquete de la homologación no usa clientes HTTP, URL ni sockets")
    void sinRed() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "org.springframework.web.client..",
                        "org.springframework.web.reactive.function.client..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.URL")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.Socket")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.HttpURLConnection")
                .check(CLASES);
    }
}
