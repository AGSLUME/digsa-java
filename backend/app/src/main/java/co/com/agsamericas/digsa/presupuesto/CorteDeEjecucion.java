package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * Lo que debe pasar, en la misma transacción, cuando entra ejecución de unos meses en unas líneas: la desviación frente a la proyección
 * al corte de cada mes, por ESM (las líneas tocadas), por fuerza y nacional.
 */
interface CorteDeEjecucion {

    void ejecucionRegistrada(Lineas.Vigencia vigencia, Set<YearMonth> periodos, List<Long> lineas, UsuarioSesion quien);
}
