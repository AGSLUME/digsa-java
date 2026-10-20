package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qué es cada endpoint para la bitácora (Anexo 5, prueba 15): módulo, acción y tipo de operación. Todo endpoint de /api la lleva:
 * lo verifica una prueba de arquitectura (equivalente a la "cobertura de las acciones" del PHP).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Accion {

    String modulo();

    String accion();

    Declarada operacion();
}
