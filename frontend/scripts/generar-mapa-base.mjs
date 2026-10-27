// Genera src/shared/colombia-departamentos.json, el mapa político base de la pantalla de mapas (SVG ya proyectado, sin servicios
// externos). No corre en el build: se ejecuta a mano cuando cambia la geometría.
//
// Insumos (dominio público o abiertos):
//   - Departamentos: Marco Geoestadístico Nacional del DANE (GeoJSON del gist 43c7656821069d00dcbc de John Guerra), simplificado
//     con `mapshaper colombia.geo.json -simplify 2.5% keep-shapes -o precision=0.0001 simple.geo.json` (al 1,2 % Pereira quedaba fuera de
//     Risaralda); las islas de San Andrés, Providencia y Santa
//     Catalina se pierden a ese nivel y se toman aparte con
//     `mapshaper colombia.geo.json -filter "DPTO=='88'" -simplify interval=150 keep-shapes -o precision=0.00001 islas.geo.json`.
//   - Países vecinos: Natural Earth 1:50m admin-0 v5.1.2 (dominio público), con
//     `mapshaper ne_50m_admin_0_countries.geojson -filter "['VEN','BRA','PER','ECU','PAN','COL','ABW','CUW'].indexOf(ADM0_A3) > -1"
//      -filter-fields ADM0_A3 -clip bbox=-80.8,-5.1,-65.8,13.5 -simplify 50% keep-shapes -o precision=0.0001 vecinos.geo.json`.
//
// Uso: copiar este archivo a una carpeta con `npm i polylabel@2` y correr
//   node generar-mapa-base.mjs simple.geo.json islas.geo.json vecinos.geo.json salida.json
import { readFileSync, writeFileSync } from 'node:fs';
import polylabel from 'polylabel';

const [entrada, entradaIslas, entradaVecinos, salida] = process.argv.slice(2);
const geo = JSON.parse(readFileSync(entrada, 'utf8'));
const geoVecinos = JSON.parse(readFileSync(entradaVecinos, 'utf8'));
const islas = JSON.parse(readFileSync(entradaIslas, 'utf8')).features[0].geometry;
geo.features.find((f) => f.properties.DPTO === '88').geometry = islas;

// Proyección equirectangular con el ancho corregido por cos(4°) (Colombia está cerca del ecuador): 1 px ≈ 2,8 km en los dos ejes
const KX = Math.cos((4 * Math.PI) / 180);
const ESCALA = 40; // px por grado
const VISTA = { lonMin: -80.3, lonMax: -66.3, latMax: 13.0, latMin: -4.6 };
const principal = { lonMin: VISTA.lonMin, latMax: VISTA.latMax, k: ESCALA, kx: ESCALA * KX, dx: 0, dy: 0 };
// San Andrés, Providencia y Santa Catalina en un recuadro arriba a la izquierda (sobre el mar Caribe), ampliado 2,4 veces
const recuadro = { lonMin: -81.8, latMax: 13.45, k: ESCALA * 2.4, kx: ESCALA * 2.4 * KX, dx: 14, dy: 14 };
const ANCHO = Math.round((VISTA.lonMax - VISTA.lonMin) * principal.kx);
const ALTO = Math.round((VISTA.latMax - VISTA.latMin) * principal.k);

const p = (pr, lon, lat) => [pr.dx + (lon - pr.lonMin) * pr.kx, pr.dy + (pr.latMax - lat) * pr.k];
const r1 = (n) => Math.round(n * 10) / 10;
const poligonos = (geom) => (geom.type === 'Polygon' ? [geom.coordinates] : geom.coordinates);

function trazo(geom, pr) {
  let d = '';
  for (const poli of poligonos(geom)) {
    for (const anillo of poli) {
      anillo.forEach(([lon, lat], i) => {
        const [x, y] = p(pr, lon, lat);
        d += (i === 0 ? 'M' : 'L') + r1(x) + ' ' + r1(y);
      });
      d += 'Z';
    }
  }
  return d;
}

const area = (anillo) => Math.abs(anillo.reduce((s, [x, y], i) => { const [x2, y2] = anillo[(i + 1) % anillo.length]; return s + x * y2 - x2 * y; }, 0)) / 2;

/** Polo de inaccesibilidad (el punto interior más lejano del borde) del polígono más grande, en coordenadas del SVG. */
function polo(geom, pr) {
  const proyectados = poligonos(geom).map((poli) => poli.map((anillo) => anillo.map(([lon, lat]) => p(pr, lon, lat))));
  const mayor = proyectados.reduce((a, b) => (area(b[0]) > area(a[0]) ? b : a));
  const [x, y] = polylabel(mayor, 0.2);
  return [r1(x), r1(y)];
}

function caja(geom, pr) {
  const xs = [], ys = [];
  for (const poli of poligonos(geom)) for (const [lon, lat] of poli[0]) { const [x, y] = p(pr, lon, lat); xs.push(x); ys.push(y); }
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)].map(r1);
}

const NOMBRES = {
  '05': 'Antioquia', '08': 'Atlántico', '11': 'Bogotá D.C.', '13': 'Bolívar', '15': 'Boyacá', '17': 'Caldas', '18': 'Caquetá',
  '19': 'Cauca', '20': 'Cesar', '23': 'Córdoba', '25': 'Cundinamarca', '27': 'Chocó', '41': 'Huila', '44': 'La Guajira',
  '47': 'Magdalena', '50': 'Meta', '52': 'Nariño', '54': 'Norte de Santander', '63': 'Quindío', '66': 'Risaralda', '68': 'Santander',
  '70': 'Sucre', '73': 'Tolima', '76': 'Valle del Cauca', '81': 'Arauca', '85': 'Casanare', '86': 'Putumayo',
  '88': 'San Andrés y Providencia', '91': 'Amazonas', '94': 'Guainía', '95': 'Guaviare', '97': 'Vaupés', '99': 'Vichada',
};
// Etiqueta en dos renglones para los nombres largos
const RENGLONES = { '54': ['Norte de', 'Santander'], '76': ['Valle del', 'Cauca'], '88': ['San Andrés y', 'Providencia'] };
// Departamentos pequeños: la etiqueta va desplazada (px) con una línea guía hasta el polo; al acercar se ve en su sitio
const GUIAS = { '08': [-26, -12], '11': [38, 4], '63': [-52, 6], '66': [-44, -6] };
// Etiquetas movidas a mano (lon, lat) donde el polo choca con una capital o con la etiqueta de un departamento pequeño
const POSICIONES = { '27': [-76.95, 6.35], '15': [-73.2, 6.15], '25': [-73.95, 5.3], '76': [-76.55, 3.85] };

// Vecindad: dos departamentos son vecinos si un vértice de uno queda a menos de TOLERANCIA grados (unos 3 km) del borde del otro.
// No basta con compartir vértices: el DANE deja fuera los cauces de los ríos grandes (Magdalena, Cauca) y ahí los bordes no se tocan.
const TOLERANCIA = 0.03;
const segmentos = (geom) => poligonos(geom).flatMap((poli) => poli.flatMap((a) => a.slice(1).map((q, i) => [a[i], q])));
const distSeg = ([px, py], [[ax, ay], [bx, by]]) => {
  const dx = bx - ax, dy = by - ay;
  const t = dx || dy ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy))) : 0;
  return Math.hypot(px - ax - t * dx, py - ay - t * dy);
};
const formas = geo.features.map((f) => {
  const pts = poligonos(f.geometry).flatMap((poli) => poli.flat());
  const xs = pts.map((q) => q[0]), ys = pts.map((q) => q[1]);
  return { codigo: f.properties.DPTO, pts, segs: segmentos(f.geometry), caja: [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)] };
});
const cerca = (a, b) => a.caja[0] - TOLERANCIA <= b.caja[2] && b.caja[0] - TOLERANCIA <= a.caja[2] && a.caja[1] - TOLERANCIA <= b.caja[3] && b.caja[1] - TOLERANCIA <= a.caja[3];
const vecinos = Object.fromEntries(formas.map((f) => [f.codigo, new Set()]));
for (const a of formas) for (const b of formas) {
  if (a.codigo >= b.codigo || !cerca(a, b)) continue;
  if (a.pts.some((q) => b.segs.some((s) => distSeg(q, s) < TOLERANCIA)) || b.pts.some((q) => a.segs.some((s) => distSeg(q, s) < TOLERANCIA))) {
    vecinos[a.codigo].add(b.codigo);
    vecinos[b.codigo].add(a.codigo);
  }
}

// Coloreado de grafo con 5 colores (DSatur): primero el de más colores distintos a su alrededor; entre los colores libres, el
// menos usado, para que la paleta quede pareja
const COLORES = 5;
const color = {};
const usos = Array(COLORES).fill(0);
const pendientes = new Set(Object.keys(vecinos));
while (pendientes.size) {
  const sat = (c) => new Set([...vecinos[c]].filter((v) => v in color).map((v) => color[v])).size;
  const sig = [...pendientes].sort((a, b) => sat(b) - sat(a) || vecinos[b].size - vecinos[a].size || a.localeCompare(b))[0];
  const ocupados = new Set([...vecinos[sig]].map((v) => color[v]));
  const libres = [...Array(COLORES).keys()].filter((c) => !ocupados.has(c));
  if (!libres.length) throw new Error('Sin color libre para ' + sig);
  color[sig] = libres.sort((a, b) => usos[a] - usos[b] || a - b)[0];
  usos[color[sig]]++;
  pendientes.delete(sig);
}

const departamentos = geo.features
  .map((f) => {
    const codigo = f.properties.DPTO;
    const pr = codigo === '88' ? recuadro : principal;
    const etiqueta = POSICIONES[codigo] ? p(pr, ...POSICIONES[codigo]).map(r1) : polo(f.geometry, pr);
    const d = {
      codigo,
      nombre: NOMBRES[codigo],
      renglones: (RENGLONES[codigo] ?? [NOMBRES[codigo]]).map((t) => t.toUpperCase()),
      color: color[codigo],
      vecinos: [...vecinos[codigo]].sort(),
      caja: caja(f.geometry, pr),
      etiqueta,
      d: trazo(f.geometry, pr),
    };
    if (GUIAS[codigo]) d.guia = [r1(etiqueta[0] + GUIAS[codigo][0]), r1(etiqueta[1] + GUIAS[codigo][1])];
    return d;
  })
  .sort((a, b) => a.codigo.localeCompare(b.codigo));

// Marco del recuadro de San Andrés; su etiqueta va debajo del marco
const [x0, y0] = p(recuadro, -81.8, 13.45);
const [x1, y1] = p(recuadro, -81.3, 12.45);
const marco = [r1(x0 - 4), r1(y0 - 4), r1(x1 - x0 + 8), r1(y1 - y0 + 8)];
const sa = departamentos.find((d) => d.codigo === '88');
sa.etiqueta = [r1(marco[0] + marco[2] / 2), r1(marco[1] + marco[3] + 9)];

// Países vecinos (y la Colombia de Natural Earth como fondo de tierra, para que no se vea mar entre los dos trazados de la frontera)
const NOMBRES_PAISES = { VEN: 'Venezuela', BRA: 'Brasil', PER: 'Perú', ECU: 'Ecuador', PAN: 'Panamá' };
const ETIQUETAS_PAISES = { VEN: [-68.4, 9.2], BRA: [-67.6, -2.6], PER: [-74.6, -3.7], ECU: [-78.7, -1.5], PAN: [-79.5, 8.2] };
const paises = geoVecinos.features
  .map((f) => {
    const c = f.properties.ADM0_A3;
    const x = { codigo: c, d: trazo(f.geometry, principal) };
    if (NOMBRES_PAISES[c]) {
      x.nombre = NOMBRES_PAISES[c];
      x.etiqueta = p(principal, ...ETIQUETAS_PAISES[c]).map(r1);
    }
    return x;
  })
  .sort((a, b) => a.codigo.localeCompare(b.codigo));
const mares = [
  { nombre: 'Mar Caribe', etiqueta: p(principal, -76.6, 12.3).map(r1) },
  { nombre: 'Océano Pacífico', etiqueta: p(principal, -79.2, 4.6).map(r1) },
];

const datos = {
  fuente:
    'Límites departamentales: Marco Geoestadístico Nacional del DANE (GeoJSON publicado por John Guerra, gist 43c7656821069d00dcbc), ' +
    'simplificado con mapshaper al 2,5 %. Países vecinos: Natural Earth 1:50m admin-0 v5.1.2 (dominio público, naturalearthdata.com), ' +
    'recortado y simplificado con mapshaper al 50 %. Etiquetas en el polo de inaccesibilidad (polylabel); colores por coloreado de grafo.',
  ancho: ANCHO,
  alto: ALTO,
  principal,
  recuadro: { ...recuadro, lonCorte: -80.5, marco },
  paises,
  mares,
  departamentos,
};
writeFileSync(salida, JSON.stringify(datos) + '\n');
console.log(ANCHO, ALTO, departamentos.length, 'usos', usos, 'bytes', JSON.stringify(datos).length);
