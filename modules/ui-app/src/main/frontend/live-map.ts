import { LngLatBounds, Map as MapLibreMap, NavigationControl, Popup } from 'maplibre-gl';
import type { GeoJSONSource } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';

/**
 * One trip position. The coordinates are the most advanced stop the trip is predicted to reach,
 * because a GTFS-RT trip update carries predictions rather than a vehicle position.
 */
interface LiveMapMarker {
  lat: number;
  lon: number;
  label: string;
  detail: string;
  delaySeconds: number | null;
}

interface MarkerProperties {
  label: string;
  detail: string;
  delayClass: 'early' | 'on-time' | 'late' | 'severe' | 'unknown';
}

/**
 * OpenFreeMap vector styles: free, no API key, no usage limits, and the style carries its own
 * attribution. Positron is a muted basemap that keeps the delay-coloured markers readable.
 */
const STYLE_LIGHT = 'https://tiles.openfreemap.org/styles/positron';
const STYLE_DARK = 'https://tiles.openfreemap.org/styles/dark';

/** Basemap sources of the OpenFreeMap styles; their failures trigger the raster fallback. */
const BASEMAP_SOURCE_IDS = ['openmaptiles', 'ne2_shaded'];
const FALLBACK_AFTER_ERRORS = 5;
const STYLE_LOAD_TIMEOUT_MS = 8000;

const SOURCE_ID = 'live-trips';
const CLUSTER_LAYER = 'live-trips-clusters';
const CLUSTER_COUNT_LAYER = 'live-trips-cluster-count';
const POINT_LAYER = 'live-trips-points';

/** Map-space tuning, in CSS-free map units: pixels below the cluster radius are merged. */
const CLUSTER_MAX_ZOOM = 12;
const CLUSTER_RADIUS = 48;

/**
 * Delay colours are literal hex values rather than theme tokens: MapLibre parses its style colours
 * itself and cannot read Aura's `oklch()` / `light-dark()` values. The hues are mid-tone so they
 * stay legible on both the light and the dark basemap.
 */
const COLOUR_EARLY = '#3b82f6';
const COLOUR_ON_TIME = '#22c55e';
const COLOUR_LATE = '#f59e0b';
const COLOUR_SEVERE = '#ef4444';
const COLOUR_UNKNOWN = '#9ca3af';
const COLOUR_CLUSTER = '#ff8904';
const COLOUR_HALO = 'rgba(255, 255, 255, 0.9)';

const DEFAULT_CENTER: [number, number] = [10.45, 51.16];
const DEFAULT_ZOOM = 5;

/**
 * The raster fallback used when the vector basemap cannot be loaded: one source, no glyphs, no
 * sprite, so it renders even where the OpenFreeMap CDN is unreachable. OpenStreetMap's tile usage
 * policy allows this light use, and the attribution is embedded in the style.
 */
function rasterFallbackStyle(): Record<string, unknown> {
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

/** Popup offset in the map's pixel space, derived from the root font size rather than hard-coded. */
function popupOffset(): number {
  const rootFontSize = parseFloat(getComputedStyle(document.documentElement).fontSize);
  return Number.isFinite(rootFontSize) ? rootFontSize : 16;
}

function delayClass(delay: number | null): MarkerProperties['delayClass'] {
  if (delay === null) {
    return 'unknown';
  }
  if (delay > 300) {
    return 'severe';
  }
  if (delay > 60) {
    return 'late';
  }
  if (delay < -60) {
    return 'early';
  }
  return 'on-time';
}

/**
 * MapLibre wrapper for the live updates view.
 *
 * <p>Flow sets the {@code markers} property with a JSON array on every poll. The trips are held in
 * a clustered GeoJSON source rather than as DOM markers, so a city full of trips stays one readable
 * cluster instead of overlapping pins; the map itself is created once and a poll only replaces the
 * source data.
 */
class LiveMap extends HTMLElement {
  private map?: MapLibreMap;
  private markerData: LiveMapMarker[] = [];
  private resizeObserver?: ResizeObserver;
  private themeObserver?: MutationObserver;
  private followMarkers = true;
  private customStyleUrl?: string;
  private basemapFailures = 0;
  private usingFallback = false;
  private styleLoaded = false;
  private fallbackTimer?: number;

  connectedCallback(): void {
    if (this.map) {
      return;
    }
    const map = new MapLibreMap({
      container: this,
      style: this.currentStyle(),
      center: DEFAULT_CENTER,
      zoom: DEFAULT_ZOOM,
      attributionControl: { compact: true },
    });
    this.map = map;
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');

    // Stop following the markers once the user pans or zooms; a poll must not yank the view back.
    map.on('dragstart', () => {
      this.followMarkers = false;
    });
    map.on('zoomstart', (event) => {
      if ((event as { originalEvent?: Event }).originalEvent) {
        this.followMarkers = false;
      }
    });

    // Fires on the first load and again after every setStyle, so the source and layers survive a
    // light/dark switch.
    map.on('style.load', () => {
      this.styleLoaded = true;
      window.clearTimeout(this.fallbackTimer);
      this.installLayers();
      this.updateSource();
    });
    // A basemap that cannot be fetched leaves an empty map with no explanation; log the failure and
    // switch to raster tiles instead of staying blank.
    map.on('error', (event) => this.onMapError(event));
    this.fallbackTimer = window.setTimeout(() => {
      if (!this.styleLoaded) {
        this.useFallback('the basemap style did not load in time');
      }
    }, STYLE_LOAD_TIMEOUT_MS);
    this.onClick();
    this.onHover();

    this.resizeObserver = new ResizeObserver(() => map.resize());
    this.resizeObserver.observe(this);

    // Vaadin flips the `theme` attribute on <html> when the user switches colour scheme.
    this.themeObserver = new MutationObserver(() => {
      if (!this.usingFallback) {
        map.setStyle(this.currentStyle());
      }
    });
    this.themeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['theme'],
    });
  }

  disconnectedCallback(): void {
    window.clearTimeout(this.fallbackTimer);
    this.resizeObserver?.disconnect();
    this.resizeObserver = undefined;
    this.themeObserver?.disconnect();
    this.themeObserver = undefined;
    this.map?.remove();
    this.map = undefined;
  }

  /** Overrides the basemap style, for example to point at a self-hosted tile server. */
  set styleUrl(url: string) {
    this.customStyleUrl = url || undefined;
    if (this.map && !this.usingFallback) {
      this.map.setStyle(this.currentStyle());
    }
  }

  /** Replaces the markers; assigned by Flow as a JSON string. */
  set markers(json: string) {
    try {
      this.markerData = JSON.parse(json || '[]') as LiveMapMarker[];
    } catch {
      this.markerData = [];
    }
    this.updateSource();
  }

  private currentStyle(): string {
    if (this.customStyleUrl) {
      return this.customStyleUrl;
    }
    const theme = document.documentElement.getAttribute('theme') ?? '';
    return theme.split(' ').includes('dark') ? STYLE_DARK : STYLE_LIGHT;
  }

  private onMapError(event: unknown): void {
    const failure = event as { error?: Error; sourceId?: string };
    console.warn('[live-map]', failure.error ?? event);
    if (failure.sourceId && BASEMAP_SOURCE_IDS.includes(failure.sourceId)) {
      this.basemapFailures += 1;
      if (this.basemapFailures >= FALLBACK_AFTER_ERRORS) {
        this.useFallback('the basemap tiles failed to load');
      }
    }
  }

  private useFallback(reason: string): void {
    const map = this.map;
    if (!map || this.usingFallback) {
      return;
    }
    this.usingFallback = true;
    console.warn(`[live-map] ${reason}; switching to raster OpenStreetMap tiles`);
    map.setStyle(rasterFallbackStyle() as never);
  }

  private installLayers(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    if (!map.getSource(SOURCE_ID)) {
      map.addSource(SOURCE_ID, {
        type: 'geojson',
        data: this.featureCollection(),
        cluster: true,
        clusterMaxZoom: CLUSTER_MAX_ZOOM,
        clusterRadius: CLUSTER_RADIUS,
      });
    }
    if (!map.getLayer(CLUSTER_LAYER)) {
      map.addLayer({
        id: CLUSTER_LAYER,
        type: 'circle',
        source: SOURCE_ID,
        filter: ['has', 'point_count'],
        paint: {
          'circle-color': COLOUR_CLUSTER,
          'circle-radius': ['step', ['get', 'point_count'], 14, 10, 18, 50, 24],
          'circle-stroke-width': 2,
          'circle-stroke-color': COLOUR_HALO,
          'circle-opacity': 0.92,
        },
      });
    }
    if (!map.getLayer(CLUSTER_COUNT_LAYER)) {
      map.addLayer({
        id: CLUSTER_COUNT_LAYER,
        type: 'symbol',
        source: SOURCE_ID,
        filter: ['has', 'point_count'],
        layout: {
          'text-field': ['get', 'point_count_abbreviated'],
          // Supplied by the OpenFreeMap glyph server the style already points at.
          'text-font': ['Noto Sans Bold'],
          'text-size': 12,
        },
        paint: { 'text-color': '#ffffff' },
      });
    }
    if (!map.getLayer(POINT_LAYER)) {
      map.addLayer({
        id: POINT_LAYER,
        type: 'circle',
        source: SOURCE_ID,
        filter: ['!', ['has', 'point_count']],
        paint: {
          'circle-color': [
            'match',
            ['get', 'delayClass'],
            'early',
            COLOUR_EARLY,
            'on-time',
            COLOUR_ON_TIME,
            'late',
            COLOUR_LATE,
            'severe',
            COLOUR_SEVERE,
            COLOUR_UNKNOWN,
          ],
          'circle-radius': 6,
          'circle-stroke-width': 2,
          'circle-stroke-color': COLOUR_HALO,
        },
      });
    }
  }

  private updateSource(): void {
    const map = this.map;
    if (!map || !map.isStyleLoaded()) {
      return;
    }
    this.installLayers();
    const source = map.getSource(SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(this.featureCollection());
    this.fitMarkers();
  }

  private featureCollection(): {
    type: 'FeatureCollection';
    features: Array<{
      type: 'Feature';
      geometry: { type: 'Point'; coordinates: [number, number] };
      properties: MarkerProperties;
    }>;
  } {
    return {
      type: 'FeatureCollection',
      features: this.markerData
        .filter((marker) => Number.isFinite(marker.lat) && Number.isFinite(marker.lon))
        .map((marker) => ({
          type: 'Feature',
          geometry: { type: 'Point', coordinates: [marker.lon, marker.lat] },
          properties: {
            label: marker.label,
            detail: marker.detail,
            delayClass: delayClass(marker.delaySeconds),
          },
        })),
    };
  }

  /** Expands clusters on click and shows the trip details of a single point. */
  private onClick(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    map.on('click', (event) => {
      if (map.getLayer(CLUSTER_LAYER)) {
        const cluster = map.queryRenderedFeatures(event.point, { layers: [CLUSTER_LAYER] })[0];
        if (cluster) {
          const clusterId = cluster.properties?.cluster_id as number;
          const coordinates = (cluster.geometry as { coordinates: [number, number] }).coordinates;
          const source = map.getSource(SOURCE_ID) as GeoJSONSource | undefined;
          source
            ?.getClusterExpansionZoom(clusterId)
            .then((zoom) => map.easeTo({ center: coordinates, zoom }))
            .catch(() => undefined);
          return;
        }
      }
      if (!map.getLayer(POINT_LAYER)) {
        return;
      }
      const point = map.queryRenderedFeatures(event.point, { layers: [POINT_LAYER] })[0];
      if (!point) {
        return;
      }
      const properties = point.properties as MarkerProperties;
      new Popup({ offset: popupOffset(), closeButton: false })
        .setLngLat((point.geometry as { coordinates: [number, number] }).coordinates)
        .setDOMContent(this.popupContent(properties))
        .addTo(map);
    });
  }

  /** Turns the pointer into a hand over anything clickable. */
  private onHover(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    for (const layer of [CLUSTER_LAYER, POINT_LAYER]) {
      map.on('mouseenter', layer, () => {
        map.getCanvas().style.cursor = 'pointer';
      });
      map.on('mouseleave', layer, () => {
        map.getCanvas().style.cursor = '';
      });
    }
  }

  private popupContent(properties: MarkerProperties): HTMLElement {
    const wrapper = document.createElement('div');
    wrapper.className = 'live-map-popup';
    const title = document.createElement('strong');
    title.textContent = properties.label;
    wrapper.appendChild(title);
    if (properties.detail) {
      const detail = document.createElement('div');
      detail.textContent = properties.detail;
      wrapper.appendChild(detail);
    }
    return wrapper;
  }

  /** Fits the view to the markers while the user has not taken over the map. */
  private fitMarkers(): void {
    const map = this.map;
    if (!map || !this.followMarkers || this.markerData.length === 0) {
      return;
    }
    const positioned = this.markerData.filter(
      (marker) => Number.isFinite(marker.lat) && Number.isFinite(marker.lon),
    );
    if (positioned.length === 0) {
      return;
    }
    if (!map.loaded()) {
      // The container has no usable size before the first style load, so fit once it does.
      map.once('load', () => this.fitMarkers());
      return;
    }
    if (positioned.length === 1) {
      map.jumpTo({ center: [positioned[0].lon, positioned[0].lat], zoom: 12 });
      return;
    }
    const bounds = new LngLatBounds();
    positioned.forEach((marker) => bounds.extend([marker.lon, marker.lat]));
    map.fitBounds(bounds, { padding: 48, maxZoom: 12, duration: 0 });
  }
}

customElements.define('live-map', LiveMap);
