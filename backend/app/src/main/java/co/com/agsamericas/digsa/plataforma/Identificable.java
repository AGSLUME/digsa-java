package co.com.agsamericas.digsa.plataforma;

/** Lo mínimo que la bitácora necesita de quien está en la sesión, sin depender del módulo de identidad. */
public interface Identificable {

    long id();

    String usuario();
}
