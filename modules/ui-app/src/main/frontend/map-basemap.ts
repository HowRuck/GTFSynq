import { setWorkerUrl } from 'maplibre-gl';
import type { Map as MapLibreMap, MapGeoJSONFeature } from 'maplibre-gl';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

// MapLibre derives its worker URL from its own module location. Once the library is bundled that
// location is the app chunk, so the worker 404s and no vector tile is ever parsed: the basemap stays
// blank. Pointing MapLibre at the worker asset Vite emits fixes it for both the dev and build output.
setWorkerUrl(maplibreWorkerUrl);
import 'maplibre-gl/dist/maplibre-gl.css';

/**
 * OpenFreeMap vector styles: free, no API key, no usage limits, and the style carries its own
 * attribution. Positron is a muted basemap that keeps data drawn on top readable.
 */
export const STYLE_LIGHT = 'https://tiles.openfreemap.org/styles/positron';
export const STYLE_DARK = 'https://tiles.openfreemap.org/styles/dark';

/** Basemap sources of the OpenFreeMap styles; their failures trigger the raster fallback. */
export const BASEMAP_SOURCE_IDS = ['openmaptiles', 'ne2_shaded'];
export const FALLBACK_AFTER_ERRORS = 5;
export const STYLE_LOAD_TIMEOUT_MS = 8000;

/** The configured style, or OpenFreeMap's light/dark style matching the active theme. */
export function styleFor(customStyleUrl?: string): string {
  if (customStyleUrl) {
    return customStyleUrl;
  }
  const theme = document.documentElement.getAttribute('theme') ?? '';
  return theme.split(' ').includes('dark') ? STYLE_DARK : STYLE_LIGHT;
}

/**
 * The object form of a MapLibre style, derived from setStyle's own signature so the fallback style
 * is checked against exactly what the map accepts.
 */
type StyleObject = Exclude<Parameters<MapLibreMap['setStyle']>[0], string | null>;

/**
 * The raster fallback used when the vector basemap cannot be loaded: one source, no glyphs, no
 * sprite, so it renders even where the OpenFreeMap CDN is unreachable. OpenStreetMap's tile usage
 * policy allows this light use, and the attribution is embedded in the style.
 */
export function rasterFallbackStyle(): StyleObject {
  return {
    version: 8,
    sources: {
      osm: {
        type: 'raster',
        tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
        tileSize: 256,
        maxzoom: 19,
        attribution:
          '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      },
    },
    layers: [
      { id: 'background', type: 'background', paint: { 'background-color': '#e8e6e1' } },
      { id: 'osm', type: 'raster', source: 'osm' },
    ],
  };
}

/**
 * Narrows a queried feature's geometry to a [lng, lat] pair. queryRenderedFeatures returns the
 * generic Geometry union, and only a Point carries coordinates.
 */
export function pointCoordinates(
  geometry: MapGeoJSONFeature['geometry'],
): [number, number] | undefined {
  if (geometry.type !== 'Point' || geometry.coordinates.length < 2) {
    return undefined;
  }
  return [geometry.coordinates[0], geometry.coordinates[1]];
}

/** Popup offset in the map's pixel space, derived from the root font size rather than hard-coded. */
export function popupOffset(): number {
  const rootFontSize = parseFloat(getComputedStyle(document.documentElement).fontSize);
  return Number.isFinite(rootFontSize) ? rootFontSize : 16;
}
