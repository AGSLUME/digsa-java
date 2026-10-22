package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import java.util.List;

/**
 * Lo que debe pasar, en la misma transacción, cuando cambia el asignado o la ejecución de unas líneas: los umbrales del 25 al 100 % se
 * recalculan (una disminución también puede cruzar un umbral).
 */
interface CambioDeSaldos {

    void saldosCambiados(Lineas.Vigencia vigencia, List<Long> lineas, UsuarioSesion quien);
}
