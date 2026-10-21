package co.com.agsamericas.digsa.ejecucion;

/** PNG válido para pruebas de otros módulos. */
public final class EjecucionApiTestPng {

    private EjecucionApiTestPng() {
    }

    public static byte[] png() {
        try {
            return EjecucionApiTest.firmaPng();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
