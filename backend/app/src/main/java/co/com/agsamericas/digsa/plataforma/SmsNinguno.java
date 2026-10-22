package co.com.agsamericas.digsa.plataforma;

/** Sin canal de SMS (local y pruebas, mientras no se contrate el proveedor): la alerta queda solo por correo y lo dice. */
public class SmsNinguno implements Sms {

    public static final String MOTIVO = "SMS no configurado";

    @Override
    public Resultado enviar(String telefono, String texto) {
        return Resultado.omitido(MOTIVO);
    }

    @Override
    public boolean real() {
        return false;
    }
}
