package co.com.agsamericas.digsa.analitica;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Generador sintético del insumo de dispensación del OPL (analitica-ingesta, «Generador sintético de 2 M filas con errores sembrados»;
 * hoja de ruta, preparación 12): un año de dispensaciones con documentos y nombres ficticios (nunca datos reales, Ley 1581), en CSV «;»
 * UTF-8 con BOM y coma decimal, con una cantidad conocida de cada causa de rechazo, y aparte el JSON de totales esperados calculados aquí,
 * sin pasar por la base: recibidos, procesados, rechazos por causa y gasto, unidades, dispensaciones y pacientes distintos en total y por
 * Fuerza, ESM, modalidad, ATC nivel 1, mes, CUM y CIE-10. Con la misma semilla produce los mismos bytes. Solo usa el JDK.
 *
 * <p>Los ESM son siglas que existen en `puntos` (V7) con su Fuerza y modalidad, más tres códigos «ESM-90x» que no existen (se aceptan y
 * cuentan como «ESM sin correspondencia»). Los medicamentos y CUM son ficticios con ATC reales.
 *
 * <p>Uso (una sola vez; el archivo de 2 M se reutiliza):
 * <pre>
 *   cd backend &amp;&amp; java app/src/test/java/co/com/agsamericas/digsa/analitica/GeneradorSintetico.java --filas 2000000 --salida ../.local/analitica
 * </pre>
 * Escribe {@code sintetico_<filas>.csv} y {@code totales_esperados_<filas>.json}. Los totales del mismo archivo fuera del sistema:
 * {@code py herramientas/totales_insumo.py .local/analitica/sintetico_2000000.csv --esm-conocidos <siglas>} da el mismo JSON.
 */
public final class GeneradorSintetico {

    public static final String ENCABEZADO = "TIPO_DOC;NUM_DOC;NOMBRE_PACIENTE;FECHA_NAC;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;"
            + "DESC_MEDICAMENTO;CUM;PRINCIPIO_ACTIVO;ATC;CIE10;CANTIDAD;VALOR_UNITARIO;VALOR_TOTAL;PROFESIONAL;TIPO_FORMULA;NUM_FORMULA";
    public static final long SEMILLA = 20251231L;
    public static final int ANIO = 2025;

    /** Causas sembradas, con el código de reglas/AnaliticaIngesta.Causa. */
    public static final List<String> CAUSAS = List.of("obligatorio_vacio", "fecha_invalida", "fecha_fuera_periodo", "numero_invalido",
            "cantidad_no_positiva", "valor_negativo", "cum_invalido", "fuerza_no_reconocida", "modalidad_no_reconocida", "duplicado_exacto",
            "campos_distintos");

    /** Resultado: archivos y lo que debe dar la ingesta. */
    public record Resultado(Path csv, Path totales, long recibidos, long procesados, long rechazados, Map<String, Long> rechazosPorCausa,
                            long esmSinCorrespondencia, BigDecimal gasto, long unidades, long pacientes, List<String> documentos) {
    }

    private record Esm(String sigla, String fuerza, String modalidad) {
    }

    private record Medicamento(String cum, String descripcion, String principio, String atc, BigDecimal precio, int cantidad, int peso) {
    }

    private record Paciente(int id, String tipo, String numero, String nacimiento, Esm esm, String[] diagnosticos) {
    }

    private static final List<Esm> ESMS = esms();
    private static final List<Esm> DESCONOCIDOS = List.of(new Esm("ESM-901", "EJC", "Dispensación"), new Esm("ESM-902", "ARC", "Suministro"),
            new Esm("ESM-903", "FAC", "Dispensación"));
    private static final List<Medicamento> MEDICAMENTOS = medicamentos();
    private static final String[] CIE10 = {"I10X", "E119", "E785", "J45X", "K219", "M545", "F329", "F419", "G409", "N40X", "E039", "I251",
        "J449", "M069", "C509", "R51X", "J029", "A09X", "L209", "H109", "K297", "M199", "E669", "I48X", "N390", "B349", "G439", "F200"};
    private static final String[] TIPOS_FORMULA = {"Ambulatoria", "Crónica", "Urgencias"};

    private static List<Esm> esms() {
        String datos = "BAS02:EJC:D BICOR:EJC:D BAS10:EJC:D BICAR:EJC:D GMRON:EJC:D BIVER:EJC:D DMBOG:ARC:D DMBAQ:ARC:D DMCOV:ARC:D "
                + "DMCOR:ARC:D DMBUN:ARC:D DMLEG:ARC:D DMEFA:FAC:D CACOM01:FAC:D CACOM02:FAC:D CACOM03:FAC:D CACOM04:FAC:D CACOM05:FAC:D "
                + "BILUD:EJC:S BITER05:EJC:S BASGO:EJC:S BITER07:EJC:S BIAVA:EJC:S BIPAT:EJC:S UBAMG:ARC:S UBAGP:ARC:S UBAIN:ARC:S DMCAR:ARC:S "
                + "UBAEN:ARC:S UBAML:ARC:S CATAM:FAC:S GAAMA:FAC:S GACAR:FAC:S GACAS:FAC:S GAORI:FAC:S CACOM06:FAC:S BITER02:EJC:T "
                + "GBMAT:EJC:T BIMUR:EJC:T BITER10:EJC:T BAEEV03:EJC:T BASRO:EJC:T";
        List<Esm> l = new ArrayList<>();
        for (String s : datos.split(" ")) {
            String[] p = s.split(":");
            l.add(new Esm(p[0], p[1], switch (p[2]) {
                case "D" -> "Dispensación";
                case "S" -> "Suministro";
                default -> "Satélite";
            }));
        }
        return List.copyOf(l);
    }

    private static List<Medicamento> medicamentos() {
        Object[][] d = {
            {"LOSARTAN 50 MG TABLETA", "LOSARTAN", "C09CA01", "180.50", 30, 30}, {"METFORMINA 850 MG TABLETA", "METFORMINA", "A10BA02", "95.20", 60, 30},
            {"ATORVASTATINA 20 MG TABLETA", "ATORVASTATINA", "C10AA05", "420.00", 30, 25}, {"ENALAPRIL 20 MG TABLETA", "ENALAPRIL", "C09AA02", "75.30", 30, 20},
            {"AMLODIPINO 5 MG TABLETA", "AMLODIPINO", "C08CA01", "110.00", 30, 20}, {"OMEPRAZOL 20 MG CAPSULA", "OMEPRAZOL", "A02BC01", "88.40", 30, 30},
            {"ACETAMINOFEN 500 MG TABLETA", "ACETAMINOFEN", "N02BE01", "45.00", 20, 40}, {"IBUPROFENO 400 MG TABLETA", "IBUPROFENO", "M01AE01", "60.10", 20, 30},
            {"SALBUTAMOL 100 MCG INHALADOR", "SALBUTAMOL", "R03AC02", "9500.00", 1, 10}, {"BECLOMETASONA 250 MCG INHALADOR", "BECLOMETASONA", "R03BA01", "28500.00", 1, 6},
            {"INSULINA GLARGINA 100 UI/ML PLUMA", "INSULINA GLARGINA", "A10AE04", "62000.00", 2, 6}, {"LEVOTIROXINA 100 MCG TABLETA", "LEVOTIROXINA", "H03AA01", "150.00", 30, 12},
            {"HIDROCLOROTIAZIDA 25 MG TABLETA", "HIDROCLOROTIAZIDA", "C03AA03", "40.00", 30, 15}, {"ACIDO ACETILSALICILICO 100 MG TABLETA", "ACIDO ACETILSALICILICO", "B01AC06", "35.00", 30, 20},
            {"CLOPIDOGREL 75 MG TABLETA", "CLOPIDOGREL", "B01AC04", "520.00", 30, 8}, {"SERTRALINA 50 MG TABLETA", "SERTRALINA", "N06AB06", "300.00", 30, 8},
            {"FLUOXETINA 20 MG CAPSULA", "FLUOXETINA", "N06AB03", "210.00", 30, 8}, {"QUETIAPINA 25 MG TABLETA", "QUETIAPINA", "N05AH04", "480.00", 30, 5},
            {"CARBAMAZEPINA 200 MG TABLETA", "CARBAMAZEPINA", "N03AF01", "260.00", 60, 5}, {"AMOXICILINA 500 MG CAPSULA", "AMOXICILINA", "J01CA04", "190.00", 21, 15},
            {"CEFALEXINA 500 MG CAPSULA", "CEFALEXINA", "J01DB01", "330.00", 28, 8}, {"AZITROMICINA 500 MG TABLETA", "AZITROMICINA", "J01FA10", "1200.00", 3, 8},
            {"LORATADINA 10 MG TABLETA", "LORATADINA", "R06AX13", "70.00", 10, 12}, {"PREDNISOLONA 5 MG TABLETA", "PREDNISOLONA", "H02AB06", "160.00", 20, 6},
            {"METOPROLOL 50 MG TABLETA", "METOPROLOL", "C07AB02", "140.00", 60, 10}, {"FUROSEMIDA 40 MG TABLETA", "FUROSEMIDA", "C03CA01", "55.00", 30, 8},
            {"ESOMEPRAZOL 40 MG TABLETA", "ESOMEPRAZOL", "A02BC05", "900.00", 30, 6}, {"TAMSULOSINA 0,4 MG CAPSULA", "TAMSULOSINA", "G04CA02", "650.00", 30, 5},
            {"ADALIMUMAB 40 MG JERINGA PRELLENADA", "ADALIMUMAB", "L04AB04", "1850000.00", 2, 1}, {"ETANERCEPT 50 MG JERINGA PRELLENADA", "ETANERCEPT", "L04AB01", "1250000.00", 4, 1},
            {"TRASTUZUMAB 440 MG VIAL", "TRASTUZUMAB", "L01FD01", "4200000.00", 1, 1}, {"IMATINIB 400 MG TABLETA", "IMATINIB", "L01EA01", "95000.00", 30, 1},
            {"ENOXAPARINA 40 MG JERINGA", "ENOXAPARINA", "B01AB05", "18500.00", 10, 3}, {"TRAMADOL 50 MG CAPSULA", "TRAMADOL", "N02AX02", "420.00", 20, 6},
            {"DICLOFENACO 50 MG TABLETA", "DICLOFENACO", "M01AB05", "50.00", 20, 15}, {"ACIDO VALPROICO 250 MG CAPSULA", "ACIDO VALPROICO", "N03AG01", "380.00", 60, 4},
            {"METOCLOPRAMIDA 10 MG TABLETA", "METOCLOPRAMIDA", "A03FA01", "48.00", 20, 8}, {"SITAGLIPTINA 100 MG TABLETA", "SITAGLIPTINA", "A10BH01", "2900.00", 30, 4},
            {"ROSUVASTATINA 10 MG TABLETA", "ROSUVASTATINA", "C10AA07", "650.00", 30, 6}, {"SALMETEROL/FLUTICASONA 50/250 MCG INHALADOR", "SALMETEROL Y FLUTICASONA", "R03AK06", "98000.00", 1, 3}};
        List<Medicamento> l = new ArrayList<>();
        for (int i = 0; i < d.length; i++) {
            String cum = (20010000 + (i + 1) * 37) + "-" + (1 + i % 3); // ficticio, con el formato del CUM
            l.add(new Medicamento(cum, (String) d[i][0], (String) d[i][1], (String) d[i][2], new BigDecimal((String) d[i][3]), (Integer) d[i][4],
                    (Integer) d[i][5]));
        }
        return List.copyOf(l);
    }

    private GeneradorSintetico() {
    }

    public static void main(String[] args) throws IOException {
        int filas = 2_000_000;
        long semilla = SEMILLA;
        Path salida = Path.of(".local", "analitica");
        boolean patrones = false;
        for (int i = 0; i < args.length; i++) {
            if ("--patrones".equals(args[i])) {
                patrones = true;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Falta el valor de " + args[i]);
            }
            switch (args[i]) {
                case "--filas" -> filas = Integer.parseInt(args[++i]);
                case "--semilla" -> semilla = Long.parseLong(args[++i]);
                case "--salida" -> salida = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Parámetro desconocido: " + args[i]);
            }
        }
        long t = System.nanoTime();
        Resultado r = generar(salida, "sintetico_" + filas + (patrones ? "_patrones" : ""), filas, semilla, patrones);
        System.out.printf("Generado %s (%d recibidos, %d procesados, %d rechazados) en %d ms%n", r.csv(), r.recibidos(), r.procesados(), r.rechazados(),
                (System.nanoTime() - t) / 1_000_000);
        System.out.println("Totales esperados: " + r.totales());
    }

    /** Siglas de los ESM que existen en `puntos` (para --esm-conocidos de totales_insumo.py). */
    public static String esmConocidos() {
        return String.join(",", ESMS.stream().map(Esm::sigla).toList());
    }

    /**
     * Genera el archivo y los totales.
     *
     * @param base nombre sin extensión: {@code <base>.csv} y {@code totales_esperados_<filas>.json}
     */
    public static Resultado generar(Path carpeta, String base, int filas, long semilla) throws IOException {
        return generar(carpeta, base, filas, semilla, false);
    }

    /**
     * Genera el archivo y los totales; con {@code patrones}, además siembra al final (con pacientes propios, todas válidas) las filas de
     * los patrones clínicos que el motor de alertas DEBE detectar y escribe en el JSON, en «riesgo», las alertas esperadas (tipo, nivel,
     * dimensión, valor, periodo y mínimo) y los conteos esperados por cohorte (analitica-cohortes-alertas, «Errores sembrados del
     * generador detectados»). Sin patrones el archivo es byte a byte el de siempre (A1–A3).
     */
    public static Resultado generar(Path carpeta, String base, int filas, long semilla, boolean patrones) throws IOException {
        if (filas < 200) {
            throw new IllegalArgumentException("Se necesitan al menos 200 filas");
        }
        Files.createDirectories(carpeta);
        Random azar = new Random(semilla);
        // Pacientes ficticios
        int nPacientes = Math.max(50, filas / 6);
        Paciente[] pacientes = new Paciente[nPacientes];
        for (int i = 0; i < nPacientes; i++) {
            double p = azar.nextDouble();
            String tipo = p < 0.80 ? "CC" : p < 0.92 ? "TI" : "CE";
            String numero = Long.toString(80_000_000L + i * 13L);
            LocalDate nac = LocalDate.of(1940, 1, 1).plusDays(azar.nextInt(80 * 365));
            double f = azar.nextDouble();
            String fuerza = f < 0.6 ? "EJC" : f < 0.85 ? "ARC" : "FAC";
            List<Esm> propias = ESMS.stream().filter(e -> e.fuerza().equals(fuerza)).toList();
            String[] dx = new String[1 + azar.nextInt(3)];
            for (int k = 0; k < dx.length; k++) {
                dx[k] = CIE10[azar.nextInt(CIE10.length)];
            }
            pacientes[i] = new Paciente(i, tipo, numero, String.format("%02d/%02d/%04d", nac.getDayOfMonth(), nac.getMonthValue(), nac.getYear()),
                    propias.get(azar.nextInt(propias.size())), dx);
        }
        int pesoTotal = MEDICAMENTOS.stream().mapToInt(Medicamento::peso).sum();

        // Errores sembrados: k filas por causa, más una con dos causas (fecha inválida y cantidad negativa)
        int k = Math.max(2, filas / 16_000);
        byte[] tipo = new byte[filas]; // 0 válida; 1..11 la causa (índice en CAUSAS + 1); 12 dos causas
        Random azarErrores = new Random(semilla ^ 0x5EEDL);
        for (int c = 0; c < CAUSAS.size(); c++) {
            for (int j = 0; j < k; j++) {
                int pos;
                do {
                    pos = 100 + azarErrores.nextInt(filas - 100);
                } while (tipo[pos] != 0);
                tipo[pos] = (byte) (c + 1);
            }
        }
        int doble;
        do {
            doble = 100 + azarErrores.nextInt(filas - 100);
        } while (tipo[doble] != 0);
        tipo[doble] = 12;

        Path csv = carpeta.resolve(base + ".csv");
        Path json = carpeta.resolve("totales_esperados_" + filas + ".json");
        Totales total = new Totales(nPacientes);
        Map<String, Map<String, Totales>> dims = new TreeMap<>();
        for (String dim : List.of("fuerza", "esm", "modalidad", "atc1", "mes", "cum", "cie10")) {
            dims.put(dim, new TreeMap<>());
        }
        Map<String, Long> porCausa = new TreeMap<>();
        long esmSin = 0;
        long procesados = 0;
        List<String> documentos = new ArrayList<>();
        Riesgo riesgo = patrones ? new Riesgo() : null;
        long sembradas = 0;
        String ultimaValida = null;
        long formula = 1_000_000;
        int itemsRestantes = 0;
        Paciente pf = null;
        LocalDate fechaF = null;
        Esm esmF = null;
        String profF = null;
        String tipoF = null;
        List<Integer> medsF = new ArrayList<>();

        try (Writer w = new BufferedWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8), 1 << 20)) {
            w.write('﻿');
            w.write(ENCABEZADO);
            w.write("\r\n");
            for (int fila = 0; fila < filas; fila++) {
                if (tipo[fila] == 10) { // duplicado exacto de la última válida
                    w.write(ultimaValida);
                    w.write("\r\n");
                    porCausa.merge("duplicado_exacto", 1L, Long::sum);
                    continue;
                }
                // Una fórmula de 1 a 3 ítems con medicamentos distintos: (fórmula, CUM) no se repite nunca
                if (itemsRestantes == 0) {
                    formula++;
                    pf = pacientes[azar.nextInt(nPacientes)];
                    fechaF = LocalDate.of(ANIO, 1, 1).plusDays(azar.nextInt(LocalDate.of(ANIO, 1, 1).lengthOfYear()));
                    double e = azar.nextDouble();
                    if (e < 0.01) {
                        Esm propio = pf.esm();
                        esmF = DESCONOCIDOS.stream().filter(x -> x.fuerza().equals(propio.fuerza())).findFirst().orElse(DESCONOCIDOS.getFirst());
                    } else if (e < 0.05) {
                        Esm propio = pf.esm();
                        List<Esm> otras = ESMS.stream().filter(x -> x.fuerza().equals(propio.fuerza())).toList();
                        esmF = otras.get(azar.nextInt(otras.size()));
                    } else {
                        esmF = pf.esm();
                    }
                    profF = "RM" + (10_000 + azar.nextInt(400));
                    tipoF = TIPOS_FORMULA[azar.nextInt(TIPOS_FORMULA.length)];
                    itemsRestantes = 1 + azar.nextInt(3);
                    medsF.clear();
                }
                int m;
                do {
                    int x = azar.nextInt(pesoTotal);
                    m = 0;
                    while (x >= MEDICAMENTOS.get(m).peso()) {
                        x -= MEDICAMENTOS.get(m).peso();
                        m++;
                    }
                } while (medsF.contains(m));
                medsF.add(m);
                itemsRestantes--;
                Medicamento med = MEDICAMENTOS.get(m);
                String cie = azar.nextDouble() < 0.03 ? "" : pf.diagnosticos()[azar.nextInt(pf.diagnosticos().length)];
                String cieTexto = cie.isEmpty() || azar.nextDouble() >= 0.1 ? cie : cie.substring(0, 3) + "." + cie.substring(3);
                int cantidad = med.cantidad() * (azar.nextDouble() < 0.2 ? 2 : 1);
                BigDecimal unitario = med.precio();
                BigDecimal valor = unitario.multiply(BigDecimal.valueOf(cantidad)).setScale(2, RoundingMode.HALF_UP);
                String numeroDoc = azar.nextDouble() < 0.15 ? conPuntos(pf.numero()) : pf.numero();
                String fuerzaTexto = variante(pf.esm().fuerza(), azar);
                String modalidadTexto = azar.nextDouble() < 0.3 ? sinTilde(esmF.modalidad()).toUpperCase() : esmF.modalidad();
                String fechaTexto = String.format("%02d/%02d/%04d", fechaF.getDayOfMonth(), fechaF.getMonthValue(), fechaF.getYear());
                String cantidadTexto = Integer.toString(cantidad);
                String valorTexto = coma(valor);
                String cumTexto = med.cum();
                String docTexto = numeroDoc;

                int t = tipo[fila];
                String causa = t >= 1 && t <= 11 ? CAUSAS.get(t - 1) : null;
                if (causa != null) {
                    switch (causa) {
                        case "obligatorio_vacio" -> docTexto = "";
                        case "fecha_invalida" -> fechaTexto = (fila % 2 == 0) ? "31/02/" + ANIO : ANIO + "-13-45";
                        case "fecha_fuera_periodo" -> fechaTexto = "15/03/2099";
                        case "numero_invalido" -> valorTexto = "12a,50";
                        case "cantidad_no_positiva" -> {
                            cantidadTexto = fila % 2 == 0 ? "0" : "-2";
                            valorTexto = "0,00";
                        }
                        case "valor_negativo" -> valorTexto = "-1500,00";
                        case "cum_invalido" -> cumTexto = "ABC-12";
                        case "fuerza_no_reconocida" -> fuerzaTexto = "POLICIA";
                        case "modalidad_no_reconocida" -> modalidadTexto = "DOMICILIO";
                        default -> {
                        }
                    }
                    porCausa.merge(causa, 1L, Long::sum);
                } else if (t == 12) {
                    fechaTexto = "31/02/" + ANIO;
                    cantidadTexto = "-2";
                    porCausa.merge("fecha_invalida", 1L, Long::sum);
                    porCausa.merge("cantidad_no_positiva", 1L, Long::sum);
                }
                String linea = String.join(";", pf.tipo(), docTexto, "PACIENTE SINTETICO " + pf.id(), pf.nacimiento(), fuerzaTexto,
                        esmF.sigla(), modalidadTexto, fechaTexto, med.descripcion(), cumTexto, med.principio(), med.atc(), cieTexto, cantidadTexto,
                        coma(unitario), valorTexto, profF, tipoF, "F" + formula);
                if ("campos_distintos".equals(causa)) {
                    linea = linea + ";EXTRA";
                }
                w.write(linea);
                w.write("\r\n");
                if (t != 0) {
                    continue;
                }
                // Válida: suma en los totales
                ultimaValida = linea;
                procesados++;
                int idPaciente = pf.id();
                if (documentos.size() < 50) {
                    documentos.add(pf.numero());
                }
                long centavos = valor.movePointRight(2).longValueExact();
                total.sumar(centavos, cantidad, idPaciente);
                dims.get("fuerza").computeIfAbsent(pf.esm().fuerza(), x -> new Totales(nPacientes)).sumar(centavos, cantidad, idPaciente);
                dims.get("esm").computeIfAbsent(esmF.sigla(), x -> new Totales(nPacientes)).sumar(centavos, cantidad, idPaciente);
                dims.get("modalidad").computeIfAbsent(esmF.modalidad(), x -> new Totales(nPacientes)).sumar(centavos, cantidad, idPaciente);
                dims.get("atc1").computeIfAbsent(med.atc().substring(0, 1), x -> new Totales(nPacientes)).sumar(centavos, cantidad, idPaciente);
                dims.get("mes").computeIfAbsent(fechaF.toString().substring(0, 7), x -> new Totales(nPacientes)).sumar(centavos, cantidad, idPaciente);
                dims.get("cum").computeIfAbsent(med.cum(), x -> new Totales(nPacientes)).sumar(centavos, cantidad, idPaciente);
                dims.get("cie10").computeIfAbsent(cie.isEmpty() ? "(sin CIE-10)" : cie, x -> new Totales(nPacientes)).sumar(centavos, cantidad, idPaciente);
                if (esmF.sigla().startsWith("ESM-")) {
                    esmSin++;
                }
                if (riesgo != null) {
                    riesgo.fila(idPaciente, fechaF, centavos, cantidad, med.principio(), med.atc(), cie, pf.esm().fuerza(), esmF.sigla());
                }
            }
            if (riesgo != null) {
                // Patrones clínicos sembrados al final (pacientes propios; todas las filas son válidas y suman en los totales)
                sembradas = sembrar(w, riesgo, total, dims, nPacientes, filas);
                procesados += sembradas;
            }
        }
        long recibidos = filas + sembradas;
        long rechazados = recibidos - procesados;
        Map<String, Object> raiz = new TreeMap<>();
        Map<String, Object> filasJson = new TreeMap<>();
        filasJson.put("recibidos", recibidos);
        filasJson.put("procesados", procesados);
        filasJson.put("rechazados", rechazados);
        raiz.put("filas", filasJson);
        raiz.put("rechazos_por_causa", new TreeMap<>(porCausa));
        raiz.put("esm_sin_correspondencia", esmSin);
        raiz.put("total", total.json());
        Map<String, Object> dimJson = new TreeMap<>();
        dims.forEach((dim, valores) -> {
            Map<String, Object> v = new TreeMap<>();
            valores.forEach((clave, tot) -> v.put(clave, tot.json()));
            dimJson.put(dim, v);
        });
        raiz.put("dimensiones", dimJson);
        if (riesgo != null) {
            raiz.put("riesgo", riesgo.json());
        }
        try (Writer w = Files.newBufferedWriter(json, StandardCharsets.UTF_8)) {
            escribirJson(w, raiz, 0);
            w.write('\n');
        }
        return new Resultado(csv, json, recibidos, procesados, rechazados, porCausa, esmSin, BigDecimal.valueOf(total.centavos, 2), total.unidades,
                total.pacientes.cardinality(), documentos);
    }

    // ---------- Patrones clínicos sembrados (A4, analitica-cohortes-alertas) ----------

    /** Lista de estrecho margen de ejemplo que la prueba publica (la misma del archivo ficticio de A2). */
    public static final List<String> ESTRECHO_EJEMPLO = List.of("ACIDO VALPROICO", "CARBAMAZEPINA", "LEVOTIROXINA");
    /** Prefijo CIE-10 de la lista ficticia de enfermedades huérfanas que carga la prueba. */
    public static final String HUERFANAS_EJEMPLO = "E75";
    /** Par de interacción ficticio que carga la prueba. */
    public static final List<String> INTERACCION_EJEMPLO = List.of("CLOPIDOGREL", "OMEPRAZOL");
    /** Indicación ficticia que carga la prueba (prefijo ATC ⇒ prefijo CIE-10 admitido): colchicina solo para gota. */
    public static final List<String> INDICACION_EJEMPLO = List.of("M04AC", "M10");
    /** Medicamento vital no disponible ficticio que publica la prueba (desabastecimiento aproximado). */
    public static final String VITAL_EJEMPLO = "PIRIDOSTIGMINA";
    /** Versión de los patrones sembrados: la prueba de 2 M regenera el archivo en caché si no coincide. */
    public static final String VERSION_PATRONES = "A4-revision-2026-10-24";
    /** Copia de AnaliticaCohortes.CRONICOS_DEFECTO (el generador solo usa el JDK; la prueba verifica que sean iguales). */
    public static final List<String> CRONICOS = List.of("I10", "I11", "I12", "I13", "I15", "E10", "E11", "E12", "E13", "E14", "E78", "J44", "J45", "N18",
            "I25", "I48", "I50", "E03", "F20", "F31", "F32", "F33", "G40", "M05", "M06", "M15", "M16", "M17", "C", "B20", "B21", "B22", "B23", "B24", "K74",
            "N40");

    private static final Medicamento APIXABAN = new Medicamento("20019001-1", "APIXABAN 5 MG TABLETA", "APIXABAN", "B01AF02", new BigDecimal("2500.00"), 60, 0);
    private static final Medicamento RIVAROXABAN = new Medicamento("20019002-1", "RIVAROXABAN 20 MG TABLETA", "RIVAROXABAN", "B01AF01",
            new BigDecimal("3800.00"), 30, 0);
    private static final Medicamento CARBAMAZEPINA_B = new Medicamento("20019003-1", "CARBAMAZEPINA 200 MG TABLETA TITULAR B", "CARBAMAZEPINA", "N03AF01",
            new BigDecimal("270.00"), 60, 0);
    private static final Medicamento TDF_FTC = new Medicamento("20019004-1", "EMTRICITABINA/TENOFOVIR 200/300 MG TABLETA", "EMTRICITABINA Y TENOFOVIR",
            "J05AR03", new BigDecimal("4500.00"), 30, 0);
    private static final Medicamento IMIGLUCERASA = new Medicamento("20019005-1", "IMIGLUCERASA 400 UI VIAL", "IMIGLUCERASA", "A16AB02",
            new BigDecimal("2500000.00"), 2, 0);
    private static final Medicamento ALOPURINOL = new Medicamento("20019006-1", "ALOPURINOL 300 MG TABLETA", "ALOPURINOL", "M04AA01",
            new BigDecimal("1700.00"), 30, 0);
    /** El mismo producto con el precio triplicado (incremento de gasto por precio, unidades estables). */
    private static final Medicamento ALOPURINOL_ALZA = new Medicamento("20019006-1", "ALOPURINOL 300 MG TABLETA", "ALOPURINOL", "M04AA01",
            new BigDecimal("5100.00"), 30, 0);
    private static final Medicamento COLCHICINA = new Medicamento("20019007-1", "COLCHICINA 0,5 MG TABLETA", "COLCHICINA", "M04AC01", new BigDecimal("150.00"),
            30, 0);
    private static final Medicamento PIRIDOSTIGMINA = new Medicamento("20019008-1", "PIRIDOSTIGMINA 60 MG TABLETA", VITAL_EJEMPLO, "N07AA02",
            new BigDecimal("900.00"), 60, 0);

    private static Medicamento med(String principio) {
        return MEDICAMENTOS.stream().filter(m -> m.principio().equals(principio)).findFirst().orElseThrow();
    }

    private static Esm esm(String sigla) {
        return ESMS.stream().filter(e -> e.sigla().equals(sigla)).findFirst().orElseThrow();
    }

    /** Escribe las filas sembradas (válidas) y las suma en los totales como el bucle principal. */
    private static final class Sembrador {
        final Writer w;
        final Riesgo r;
        final Totales total;
        final Map<String, Map<String, Totales>> dims;
        final int nPacientes;
        int pacientes;
        long filas;
        int formula;

        Sembrador(Writer w, Riesgo r, Totales total, Map<String, Map<String, Totales>> dims, int nPacientes) {
            this.w = w;
            this.r = r;
            this.total = total;
            this.dims = dims;
            this.nPacientes = nPacientes;
        }

        /** Un paciente sembrado nuevo (documento ficticio 9xxxxxxx). */
        int nuevo() {
            return pacientes++;
        }

        void fila(int sembrado, Esm e, LocalDate fecha, Medicamento m, String descripcion, String cie, int cantidad, String profesional) throws IOException {
            fila(sembrado, e.fuerza(), e, fecha, m, descripcion, cie, cantidad, profesional);
        }

        /** Con la Fuerza del paciente explícita: un ESM de otra Fuerza (ESM compartido, como el HOMIL). */
        void fila(int sembrado, String fuerza, Esm e, LocalDate fecha, Medicamento m, String descripcion, String cie, int cantidad, String profesional)
                throws IOException {
            int id = nPacientes + sembrado;
            BigDecimal valor = m.precio().multiply(BigDecimal.valueOf(cantidad)).setScale(2, RoundingMode.HALF_UP);
            String linea = String.join(";", "CC", Long.toString(90_000_000L + sembrado), "PACIENTE SEMBRADO " + sembrado, "01/01/1970", fuerza, e.sigla(),
                    e.modalidad(), String.format("%02d/%02d/%04d", fecha.getDayOfMonth(), fecha.getMonthValue(), fecha.getYear()), descripcion, m.cum(),
                    m.principio(), m.atc(), cie, Integer.toString(cantidad), coma(m.precio()), coma(valor), profesional, "Crónica", "S" + (++formula));
            w.write(linea);
            w.write("\r\n");
            filas++;
            long centavos = valor.movePointRight(2).longValueExact();
            total.sumar(centavos, cantidad, id);
            dims.get("fuerza").computeIfAbsent(fuerza, x -> new Totales(nPacientes)).sumar(centavos, cantidad, id);
            dims.get("esm").computeIfAbsent(e.sigla(), x -> new Totales(nPacientes)).sumar(centavos, cantidad, id);
            dims.get("modalidad").computeIfAbsent(e.modalidad(), x -> new Totales(nPacientes)).sumar(centavos, cantidad, id);
            dims.get("atc1").computeIfAbsent(m.atc().substring(0, 1), x -> new Totales(nPacientes)).sumar(centavos, cantidad, id);
            dims.get("mes").computeIfAbsent(fecha.toString().substring(0, 7), x -> new Totales(nPacientes)).sumar(centavos, cantidad, id);
            dims.get("cum").computeIfAbsent(m.cum(), x -> new Totales(nPacientes)).sumar(centavos, cantidad, id);
            dims.get("cie10").computeIfAbsent(cie, x -> new Totales(nPacientes)).sumar(centavos, cantidad, id);
            r.fila(id, fecha, centavos, cantidad, m.principio(), m.atc(), cie, fuerza, e.sigla());
        }

        void fila(int sembrado, Esm e, LocalDate fecha, Medicamento m, String cie, int cantidad) throws IOException {
            fila(sembrado, e, fecha, m, m.descripcion(), cie, cantidad, "RM" + (20_000 + sembrado % 50));
        }
    }

    private static LocalDate f(int mes, int dia) {
        return LocalDate.of(ANIO, mes, dia);
    }

    /**
     * Siembra los patrones y anota las alertas esperadas. Devuelve las filas escritas. Los patrones que compiten con el volumen del insumo (alto
     * costo por ESM, concentración en un profesional) crecen con él: se detectan igual a 20 000 y a 2 M filas.
     */
    private static long sembrar(Writer w, Riesgo r, Totales total, Map<String, Map<String, Totales>> dims, int nPacientes, int filas) throws IOException {
        Sembrador s = new Sembrador(w, r, total, dims, nPacientes);
        String periodo;
        // 1. Duplicidad terapéutica: 14 pacientes con apixabán y rivaroxabán (mismo ATC4 B01AF) a 15 días, en mayo
        List<String> ejc = List.of("BAS02", "BICOR", "BAS10", "BICAR", "GMRON", "BIVER");
        for (int i = 0; i < 14; i++) {
            int p = s.nuevo();
            Esm e = esm(ejc.get(i % ejc.size()));
            s.fila(p, e, f(5, 3), APIXABAN, "I48X", 60);
            s.fila(p, e, f(5, 18), RIVAROXABAN, "I48X", 30);
        }
        r.esperar("duplicidad_terapeutica", "nacional", "", "atc4", "B01AF", "2025-05", "pacientes", 14, null);
        // 2. Alto costo: pacientes nuevos con trastuzumab cuatro veces en septiembre en el DMBOG (8 a 20 000 filas; crece con el insumo para
        // superar con holgura los ingresos habituales del ESM, que también crecen)
        int altoCosto = 8 + filas / 40_000;
        for (int i = 0; i < altoCosto; i++) {
            int p = s.nuevo();
            for (int d : new int[] {2, 9, 16, 23}) {
                s.fila(p, esm("DMBOG"), f(9, d), med("TRASTUZUMAB"), "C509", 1);
            }
        }
        r.esperar("alto_costo", "esm", "DMBOG", "esm", "DMBOG", "2025-09", "pacientes", altoCosto, null);
        // 3. Polimedicados: 6 pacientes con 5 principios activos cada mes de mayo a octubre en el DMBAQ (entran en octubre, ya fuera del
        // calentamiento de la cohorte: 6 meses más la ventana de referencia de 3)
        for (int i = 0; i < 6; i++) {
            int p = s.nuevo();
            for (int mes = 5; mes <= 10; mes++) {
                for (String pa : List.of("LOSARTAN", "METFORMINA", "ATORVASTATINA", "OMEPRAZOL", "HIDROCLOROTIAZIDA")) {
                    s.fila(p, esm("DMBAQ"), f(mes, 10), med(pa), "I10X", med(pa).cantidad());
                }
            }
        }
        r.esperar("polimedicados", "esm", "DMBAQ", "esm", "DMBAQ", "2025-10", "pacientes", 6, null);
        // 4. Cambio de marca en estrecho margen: 4 pacientes con carbamazepina de un CUM en junio y de otro en julio, en el BAS02
        for (int i = 0; i < 4; i++) {
            int p = s.nuevo();
            s.fila(p, esm("BAS02"), f(6, 10), med("CARBAMAZEPINA"), "G409", 60);
            s.fila(p, esm("BAS02"), f(7, 10), CARBAMAZEPINA_B, "G409", 60);
        }
        r.esperar("marca_estrecho_margen", "esm", "BAS02", "principio", "CARBAMAZEPINA", "2025-07", "pacientes", 4, null).put("fuerza", "EJC");
        // 4b. ESM compartido por dos Fuerzas (como el HOMIL): 3 pacientes de la Armada con el mismo cambio de marca en el BAS02 (del Ejército);
        // uno de ellos también dispensa en el DMBOG (un paciente en dos ESM: el nacional no es la suma de los ESM)
        for (int i = 0; i < 3; i++) {
            int p = s.nuevo();
            s.fila(p, "ARC", esm("BAS02"), f(6, 12), med("CARBAMAZEPINA"), med("CARBAMAZEPINA").descripcion(), "G409", 60, "RM20100");
            s.fila(p, "ARC", esm("BAS02"), f(7, 12), CARBAMAZEPINA_B, CARBAMAZEPINA_B.descripcion(), "G409", 60, "RM20100");
            if (i == 0) {
                s.fila(p, esm("DMBOG"), f(7, 25), CARBAMAZEPINA_B, "G409", 60);
            }
        }
        r.esperar("marca_estrecho_margen", "esm", "BAS02", "principio", "CARBAMAZEPINA", "2025-07", "pacientes", 3, null).put("fuerza", "ARC");
        // 5. Formulación inusual: 6 dispensaciones de losartán con 600 unidades (20 veces lo habitual) en noviembre
        for (int i = 0; i < 6; i++) {
            s.fila(s.nuevo(), esm("BICAR"), f(11, 5), med("LOSARTAN"), "I10X", 600);
        }
        r.esperar("formulacion_inusual", "nacional", "", "cum", med("LOSARTAN").cum(), "2025-11", "registros", 6, null);
        // 6. Presentación inconsistente: el CUM del amlodipino de 5 mg llega como «10 MG» en 12 registros de marzo
        Medicamento amlo = med("AMLODIPINO");
        for (int i = 0; i < 6; i++) {
            int p = s.nuevo();
            s.fila(p, esm("BAS10"), f(3, 3), amlo, "AMLODIPINO 10 MG TABLETA", "I10X", 30, "RM21000");
            s.fila(p, esm("BAS10"), f(3, 17), amlo, "AMLODIPINO 10 MG TABLETA", "I10X", 30, "RM21000");
        }
        r.esperar("presentacion_inconsistente", "nacional", "", "cum", amlo.cum(), "2025-03", "registros", 12, null);
        // 7. Concentración de prescripciones: dispensaciones de adalimumab en el BAS10 en noviembre, el 75 % de un mismo profesional sobre
        // TODAS las del ESM y el mes (las del insumo incluidas: a 2 M filas hay más; 24 sembradas a 20 000)
        long existentes = r.filas("BAS10|ADALIMUMAB|10");
        int sembradasAda = (int) Math.max(24, 3 * existentes);
        long delProfesional = (long) Math.ceil(0.75 * (sembradasAda + existentes));
        for (int n = 0; n < sembradasAda; n++) {
            int p = n % 2 == 0 ? s.nuevo() : s.pacientes - 1;
            String prof = n < delProfesional ? "RM99999" : "RM9" + (1000 + n);
            s.fila(p, esm("BAS10"), f(11, n % 2 == 0 ? 4 : 18), med("ADALIMUMAB"), med("ADALIMUMAB").descripcion(), "M069", 2, prof);
        }
        r.esperar("concentracion_prescripciones", "esm", "BAS10", "principio", "ADALIMUMAB", "2025-11", "registros", sembradasAda, null).put("fuerza", "EJC");
        // 8. Incremento abrupto del consumo: las unidades de N02 del Ejército en octubre pasan a 2,6 veces la media de julio a septiembre
        long base = (r.unidades("EJC|N02", 6) + r.unidades("EJC|N02", 7) + r.unidades("EJC|N02", 8)) / 3;
        long falta = Math.max(0, base * 26 / 10 - r.unidades("EJC|N02", 9));
        int filasN02 = (int) Math.ceil(falta / 20.0);
        for (int i = 0; i < filasN02; i += 4) {
            int p = s.nuevo();
            for (int k = 0; k < 4 && i + k < filasN02; k++) {
                s.fila(p, esm(List.of("BAS02", "BICOR", "BAS10").get(i / 4 % 3)), f(10, 2 + 7 * k), med("ACETAMINOFEN"), "R51X", 20);
            }
        }
        r.esperar("incremento_consumo", "fuerza", "EJC", "atc2", "N02", "2025-10", "observado", 100, null);
        // 9 a 11. Renal, VIH y huérfanas (cohortes por diagnóstico y por ATC)
        for (int i = 0; i < 5; i++) {
            s.fila(s.nuevo(), esm("BICOR"), f(4, 8), med("LOSARTAN"), "N189", 30);
        }
        for (int i = 0; i < 4; i++) {
            s.fila(s.nuevo(), esm("DMCOV"), f(6, 12), TDF_FTC, "B24X", 30);
        }
        for (int i = 0; i < 3; i++) {
            s.fila(s.nuevo(), esm("CACOM01"), f(2, 14), IMIGLUCERASA, "E752", 2);
        }
        // 12. Interacción (solo con el par ficticio cargado): omeprazol y clopidogrel a 7 días en diciembre
        for (int i = 0; i < 3; i++) {
            int p = s.nuevo();
            s.fila(p, esm("BIVER"), f(12, 2), med("OMEPRAZOL"), "I251", 30);
            s.fila(p, esm("BIVER"), f(12, 9), med("CLOPIDOGREL"), "I251", 30);
        }
        periodo = "2025-12";
        r.esperar("interaccion", "nacional", "", "par", "CLOPIDOGREL + OMEPRAZOL", periodo, "pacientes", 3, "interacciones");
        // 13. Incremento de gasto por precio: 30 pacientes de la FAC con alopurinol (solo sembrado; 30 dispensaciones al mes, el volumen
        // mínimo para juzgar un incremento), estable de enero a septiembre; en octubre el precio se triplica con las mismas unidades (+200 %)
        for (int i = 0; i < 30; i++) {
            int p = s.nuevo();
            for (int mes = 1; mes <= 10; mes++) {
                s.fila(p, esm("DMEFA"), f(mes, 5), mes == 10 ? ALOPURINOL_ALZA : ALOPURINOL, "M109", 30);
            }
        }
        r.esperar("incremento_gasto", "fuerza", "FAC", "principio", "ALOPURINOL", "2025-10", "observado", 100, null);
        // 14. Uso fuera de indicación (solo con la indicación ficticia cargada: colchicina para gota, M10): 3 pacientes con colchicina y un
        // diagnóstico de gastritis en noviembre
        for (int i = 0; i < 3; i++) {
            s.fila(s.nuevo(), esm("BIVER"), f(11, 7), COLCHICINA, "K297", 30);
        }
        r.esperar("fuera_indicacion", "nacional", "", "atc5", COLCHICINA.atc(), "2025-11", "pacientes", 3, "indicaciones");
        // 15. Desabastecimiento aproximado (solo con el vital ficticio publicado): 12 dispensaciones de piridostigmina al mes de junio a agosto
        // en el GMRON y ninguna en septiembre (caída del 100 %)
        List<Integer> pir = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            pir.add(s.nuevo());
        }
        for (int mes = 6; mes <= 8; mes++) {
            for (int p : pir) {
                s.fila(p, esm("GMRON"), f(mes, 15), PIRIDOSTIGMINA, "G700", 60);
            }
        }
        r.esperar("desabastecimiento", "esm", "GMRON", "principio", VITAL_EJEMPLO, "2025-09", "observado", 100, "vitales").put("fuerza", "EJC");
        return s.filas;
    }

    /** Lo que el generador sabe para calcular las cohortes esperadas sin la base (mismas reglas de A4) y la base de los incrementos. */
    private static final class Riesgo {
        final Map<Integer, long[]> gasto = new HashMap<>();
        final Map<Integer, long[]> principios = new HashMap<>();
        final Map<String, Integer> indice = new HashMap<>();
        final Map<Integer, java.util.Set<String>> cronicos = new HashMap<>();
        final BitSet renal = new BitSet();
        final BitSet vih = new BitSet();
        final BitSet biologicos = new BitSet();
        final BitSet estrecho = new BitSet();
        final BitSet huerfanas = new BitSet();
        final Map<String, long[]> unidades = new HashMap<>();
        final List<Map<String, Object>> esperadas = new ArrayList<>();
        /** Filas por «ESM|principio|mes (0–11)» (la base de la concentración sembrada). */
        final Map<String, Long> porEsmPrincipio = new HashMap<>();

        long filas(String llave) {
            return porEsmPrincipio.getOrDefault(llave, 0L);
        }

        void fila(int p, LocalDate fecha, long centavos, long cantidad, String principio, String atc, String cie, String fuerza, String esm) {
            int m = fecha.getMonthValue() - 1;
            porEsmPrincipio.merge(esm + "|" + principio + "|" + m, 1L, Long::sum);
            gasto.computeIfAbsent(p, x -> new long[12])[m] += centavos;
            int i = indice.computeIfAbsent(principio, x -> indice.size());
            if (i >= 64) {
                throw new IllegalStateException("Más de 64 principios activos");
            }
            principios.computeIfAbsent(p, x -> new long[12])[m] |= 1L << i;
            if (prefijo(cie, List.of("N18", "N19", "Z49", "Z992"))) {
                renal.set(p);
            }
            if (prefijo(cie, List.of("B20", "B21", "B22", "B23", "B24", "Z21")) || prefijo(atc, List.of("J05AE", "J05AF", "J05AG", "J05AR"))) {
                vih.set(p);
            }
            if (prefijo(atc, List.of("L04AB", "L04AC", "L01F"))) {
                biologicos.set(p);
            }
            if (ESTRECHO_EJEMPLO.contains(principio)) {
                estrecho.set(p);
            }
            if (cie != null && cie.startsWith(HUERFANAS_EJEMPLO)) {
                huerfanas.set(p);
            }
            if (cie != null && cie.length() >= 3 && prefijo(cie, CRONICOS)) {
                cronicos.computeIfAbsent(p, x -> new java.util.HashSet<>()).add(cie.substring(0, 3));
            }
            unidades.computeIfAbsent(fuerza + "|" + atc.substring(0, 3), x -> new long[12])[m] += cantidad;
        }

        long unidades(String llave, int mes) {
            long[] u = unidades.get(llave);
            return u == null ? 0 : u[mes];
        }

        private static boolean prefijo(String c, List<String> l) {
            return c != null && l.stream().anyMatch(c::startsWith);
        }

        Map<String, Object> esperar(String tipo, String nivel, String codigoNivel, String dimension, String valor, String periodo, String medida, long minimo,
                                    String requiere) {
            Map<String, Object> m = new TreeMap<>();
            m.put("tipo", tipo);
            m.put("nivel", nivel);
            m.put("codigo_nivel", codigoNivel);
            m.put("dimension", dimension);
            m.put("valor", valor);
            m.put("periodo", periodo);
            m.put("medida", medida);
            m.put("minimo", minimo);
            m.put("requiere", requiere == null ? "" : requiere);
            esperadas.add(m);
            return m;
        }

        /** Conteos esperados por cohorte: pacientes distintos que son miembros en algún mes del año (mismas reglas que AnaliticaCohortes). */
        Map<String, Object> json() {
            Map<String, Object> c = new TreeMap<>();
            c.put("renal", (long) renal.cardinality());
            c.put("vih", (long) vih.cardinality());
            c.put("biologicos", (long) biologicos.cardinality());
            c.put("estrecho_margen", (long) estrecho.cardinality());
            c.put("huerfanas", (long) huerfanas.cardinality());
            c.put("pluripatologicos", cronicos.values().stream().filter(x -> x.size() >= 2).count());
            long poliformulados = 0;
            long polimedicados = 0;
            for (long[] meses : principios.values()) {
                boolean form = false;
                boolean medi = false;
                int racha = 0;
                for (int m = 0; m < 12; m++) {
                    boolean cinco = Long.bitCount(meses[m]) >= 5;
                    form |= cinco;
                    racha = cinco ? racha + 1 : 0;
                    medi |= racha >= 6;
                }
                poliformulados += form ? 1 : 0;
                polimedicados += medi ? 1 : 0;
            }
            c.put("poliformulados", poliformulados);
            c.put("polimedicados", polimedicados);
            c.put("alto_costo", altoCosto());
            c.put("control_especial", "no_evaluada");
            Map<String, Object> r = new TreeMap<>();
            r.put("cohortes_esperadas", c);
            r.put("alertas_esperadas", esperadas);
            r.put("lista_estrecho_margen", ESTRECHO_EJEMPLO);
            r.put("huerfanas_prefijos", List.of(HUERFANAS_EJEMPLO));
            r.put("interacciones", List.of(INTERACCION_EJEMPLO));
            r.put("indicaciones", List.of(INDICACION_EJEMPLO));
            r.put("vitales", List.of(VITAL_EJEMPLO));
            r.put("version_patrones", VERSION_PATRONES);
            return r;
        }

        /** Alto costo: gasto en 12 meses móviles ≥ percentil 99 (percentile_disc) del de los pacientes con dispensaciones en el mes. */
        long altoCosto() {
            BitSet miembros = new BitSet();
            for (int m = 0; m < 12; m++) {
                List<long[]> activos = new ArrayList<>();
                for (Map.Entry<Integer, long[]> e : gasto.entrySet()) {
                    if (e.getValue()[m] > 0) {
                        long s = 0;
                        for (int k = 0; k <= m; k++) {
                            s += e.getValue()[k];
                        }
                        activos.add(new long[] {e.getKey(), s});
                    }
                }
                if (activos.isEmpty()) {
                    continue;
                }
                long[] orden = activos.stream().mapToLong(x -> x[1]).sorted().toArray();
                long umbral = orden[Math.max(0, (int) Math.ceil(0.99 * orden.length) - 1)];
                for (long[] a : activos) {
                    if (a[1] > 0 && a[1] >= umbral) {
                        miembros.set((int) a[0]);
                    }
                }
            }
            return miembros.cardinality();
        }
    }

    private static String variante(String fuerza, Random azar) {
        double p = azar.nextDouble();
        return switch (fuerza) {
            case "EJC" -> p < 0.7 ? "EJC" : p < 0.9 ? "Ejército" : "EJERCITO NACIONAL";
            case "ARC" -> p < 0.8 ? "ARC" : "Armada";
            default -> p < 0.8 ? "FAC" : "Fuerza Aeroespacial";
        };
    }

    private static String sinTilde(String s) {
        return s.replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u');
    }

    private static String conPuntos(String n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = sb.length() - 3; i > 0; i -= 3) {
            sb.insert(i, '.');
        }
        return sb.toString();
    }

    private static String coma(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** Totales de una celda: gasto en centavos, unidades, dispensaciones y pacientes distintos (BitSet por id de paciente). */
    private static final class Totales {
        long centavos;
        long unidades;
        long dispensaciones;
        final BitSet pacientes;

        Totales(int n) {
            this.pacientes = new BitSet(n);
        }

        void sumar(long c, long u, int paciente) {
            centavos += c;
            unidades += u;
            dispensaciones++;
            pacientes.set(paciente);
        }

        Map<String, Object> json() {
            Map<String, Object> m = new TreeMap<>();
            m.put("gasto", BigDecimal.valueOf(centavos, 2).toPlainString());
            m.put("unidades", Long.toString(unidades));
            m.put("dispensaciones", dispensaciones);
            m.put("pacientes", (long) pacientes.cardinality());
            return m;
        }
    }

    /** JSON con el mismo formato que json.dumps(indent=2, sort_keys=True, ensure_ascii=False) de Python (para comparar byte a byte). */
    @SuppressWarnings("unchecked")
    static void escribirJson(Writer w, Object v, int nivel) throws IOException {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                w.write("{}");
                return;
            }
            w.write("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                w.write("  ".repeat(nivel + 1));
                cadena(w, e.getKey());
                w.write(": ");
                escribirJson(w, e.getValue(), nivel + 1);
                if (++i < m.size()) {
                    w.write(",");
                }
                w.write("\n");
            }
            w.write("  ".repeat(nivel));
            w.write("}");
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                w.write("[]");
                return;
            }
            w.write("[\n");
            for (int i = 0; i < l.size(); i++) {
                w.write("  ".repeat(nivel + 1));
                escribirJson(w, l.get(i), nivel + 1);
                if (i + 1 < l.size()) {
                    w.write(",");
                }
                w.write("\n");
            }
            w.write("  ".repeat(nivel));
            w.write("]");
        } else if (v instanceof Number n) {
            w.write(n.toString());
        } else {
            cadena(w, String.valueOf(v));
        }
    }

    private static void cadena(Writer w, String s) throws IOException {
        w.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> w.write("\\\"");
                case '\\' -> w.write("\\\\");
                case '\n' -> w.write("\\n");
                default -> {
                    if (c < 0x20) {
                        w.write(String.format("\\u%04x", (int) c));
                    } else {
                        w.write(c);
                    }
                }
            }
        }
        w.write('"');
    }

    /** Lectura simple de los totales por dimensión (para las pruebas de cuadre). */
    public static Map<String, Map<String, String[]>> leerDimensiones(Path json) throws IOException {
        Map<String, Map<String, String[]>> r = new HashMap<>();
        String dim = null;
        String clave = null;
        String[] valores = null;
        boolean enDimensiones = false;
        for (String l : Files.readAllLines(json, StandardCharsets.UTF_8)) {
            String t = l.strip();
            int nivel = (l.length() - l.stripLeading().length()) / 2;
            if (t.startsWith("\"dimensiones\"")) {
                enDimensiones = true;
                continue;
            }
            if (!enDimensiones) {
                continue;
            }
            if (nivel == 1 && t.startsWith("}")) {
                enDimensiones = false;
                continue;
            }
            if (nivel == 2 && t.endsWith("{")) {
                dim = t.substring(1, t.indexOf('"', 1));
                r.put(dim, new TreeMap<>());
            } else if (nivel == 3 && t.endsWith("{")) {
                clave = t.substring(1, t.lastIndexOf("\": {"));
                valores = new String[4];
                r.get(dim).put(clave, valores);
            } else if (nivel == 4 && valores != null) {
                String k = t.substring(1, t.indexOf('"', 1));
                String v = t.substring(t.indexOf(':') + 1).strip().replace(",", "").replace("\"", "");
                switch (k) {
                    case "dispensaciones" -> valores[0] = v;
                    case "gasto" -> valores[1] = v;
                    case "pacientes" -> valores[2] = v;
                    default -> valores[3] = v;
                }
            }
        }
        return r;
    }
}
