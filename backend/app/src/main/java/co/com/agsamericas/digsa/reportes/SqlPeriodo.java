package co.com.agsamericas.digsa.reportes;

import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;

/**
 * Etiqueta SQL (PostgreSQL) del periodo de una agrupación: la misma que {@link Agrupacion#etiqueta}. Los proveedores la usan en el
 * SELECT y en el GROUP BY de sus reportes agregados.
 */
public final class SqlPeriodo {

    private SqlPeriodo() {
    }

    /**
     * @param expresion expresión de tipo {@code date} o {@code timestamp} ya en hora de Bogotá (para un timestamptz:
     *                  {@code (x AT TIME ZONE 'America/Bogota')}); viene del código del proveedor, nunca del cliente
     */
    public static String etiqueta(Agrupacion a, String expresion) {
        String e = expresion;
        return switch (a) {
            case HORA -> "to_char(" + e + ", 'YYYY-MM-DD HH24\"h\"')";
            case DIA -> "to_char(" + e + ", 'YYYY-MM-DD')";
            case SEMANA -> "to_char(" + e + ", 'IYYY-\"W\"IW')";
            case MES -> "to_char(" + e + ", 'YYYY-MM')";
            case BIMESTRE -> "(to_char(" + e + ", 'YYYY') || '-B' || ((extract(month FROM " + e + ")::int - 1) / 2 + 1))";
            case TRIMESTRE -> "to_char(" + e + ", 'YYYY-\"T\"Q')";
            case SEMESTRE -> "(to_char(" + e + ", 'YYYY') || '-S' || CASE WHEN extract(month FROM " + e + ") <= 6 THEN 1 ELSE 2 END)";
            case ANIO -> "to_char(" + e + ", 'YYYY')";
        };
    }
}
