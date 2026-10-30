package co.com.agsamericas.digsa.plataforma;

import java.util.List;

/** Las plantillas de correo de un módulo (un bean por módulo): el catálogo las reúne para la pantalla Correos. */
public interface ProveedorPlantillas {

    List<Plantilla> plantillas();
}
