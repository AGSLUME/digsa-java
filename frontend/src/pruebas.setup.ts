// Vitest: jsdom no trae matchMedia y Mantine lo usa (MantineProvider, useMediaQuery). Las pruebas en node no tienen window.
if (typeof window !== 'undefined') {
  window.matchMedia ??= ((q: string) => ({
    matches: false,
    media: q,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}
