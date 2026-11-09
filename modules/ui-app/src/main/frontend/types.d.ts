/**
 * Vite bundles CSS imports; TypeScript only needs to know that a package stylesheet can be
 * imported for its side effects.
 */
declare module '*.css';

/**
 * Vite emits the referenced file as an asset and returns its URL. Used for the MapLibre worker,
 * whose own module-relative URL does not survive bundling.
 */
declare module '*?worker&url' {
  const url: string;
  export default url;
}

/**
 * Vite inlines the referenced file as a string. Used for the vendored transit-mode SVGs, which
 * the map element rasterises at runtime.
 */
declare module '*?raw' {
  const content: string;
  export default content;
}
