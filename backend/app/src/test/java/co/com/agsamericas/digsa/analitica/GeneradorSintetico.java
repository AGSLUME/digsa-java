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
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--filas" -> filas = Integer.parseInt(args[i + 1]);
                case "--semilla" -> semilla = Long.parseLong(args[i + 1]);
                case "--salida" -> salida = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("Parámetro desconocido: " + args[i]);
            }
        }
        long t = System.nanoTime();
        Resultado r = generar(salida, "sintetico_" + filas, filas, semilla);
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
            }
        }
        long rechazados = filas - procesados;
        Map<String, Object> raiz = new TreeMap<>();
        Map<String, Object> filasJson = new TreeMap<>();
        filasJson.put("recibidos", (long) filas);
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
        try (Writer w = Files.newBufferedWriter(json, StandardCharsets.UTF_8)) {
            escribirJson(w, raiz, 0);
            w.write('\n');
        }
        return new Resultado(csv, json, filas, procesados, rechazados, porCausa, esmSin, BigDecimal.valueOf(total.centavos, 2), total.unidades,
                total.pacientes.cardinality(), documentos);
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
