package co.com.agsamericas.digsa;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import co.com.agsamericas.digsa.plataforma.Accion;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reglas de arquitectura: módulos de Spring Modulith, reglas puras sin framework y bitácora en todo endpoint. */
class ArquitecturaTest {

    private static final JavaClasses CLASES = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("co.com.agsamericas.digsa");

    @Test
    @DisplayName("los módulos de Spring Modulith no usan el interior de otros ni forman ciclos")
    void modulos() {
        ApplicationModules.of(DigsaApplication.class).verify();
    }

    @Test
    @DisplayName("las reglas de negocio no dependen de Spring, JPA, jOOQ, JDBC ni servlets")
    void reglasPuras() {
        noClasses().that().resideInAPackage("co.com.agsamericas.digsa.reglas..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "org.jooq..", "java.sql..",
                        "org.hibernate..")
                .check(CLASES);
    }

    @Test
    @DisplayName("todo endpoint declara su @Accion para la bitácora (Anexo 5, prueba 15)")
    void accionEnTodoEndpoint() {
        methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and().areAnnotatedWith(GetMapping.class).or().areAnnotatedWith(PostMapping.class).or().areAnnotatedWith(PutMapping.class)
                .or().areAnnotatedWith(DeleteMapping.class).or().areAnnotatedWith(PatchMapping.class)
                .should().beAnnotatedWith(Accion.class)
                .check(CLASES);
    }
}
