import { LngLatBounds, Map as MapLibreMap, Marker, NavigationControl, Popup } from 'maplibre-gl';
import type { GeoJSONSource } from 'maplibre-gl';
import type { Feature, FeatureCollection, LineString, Point } from 'geojson';
import {
  BASEMAP_SOURCE_IDS,
  FALLBACK_AFTER_ERRORS,
  STYLE_LOAD_TIMEOUT_MS,
  popupOffset,
  rasterFallbackStyle,
  styleFor,
} from './map-basemap';

/**
 * One stop as a compact tuple: [lat, lon, name, state].
 *
 * <p>The state is the wire value of TripMap.StopState: upcoming, reached or current. Keeping the
 * tuple short matters less here than on the overview map (a trip has at most a few hundred stops),
 * but it keeps both map elements on the same simple contract.
 */
type TripStop = [number, number, string, number];

const STATE_UPCOMING = 0;
const STATE_REACHED = 1;
const STATE_CURRENT = 2;

const ROUTE_SOURCE = 'trip-path';
const ROUTE_LAYER = 'trip-path-line';
const ROUTE_DONE_SOURCE = 'trip-path-done';
const ROUTE_DONE_LAYER = 'trip-path-done-line';
const STOPS_SOURCE = 'trip-stops';
const STOP_LAYER = 'trip-stops-dots';

const DEFAULT_CENTER: [number, number] = [10.45, 51.16];
const DEFAULT_ZOOM = 5;

/** Delay-style colours matching the live overview map, readable on both basemaps. */
const COLOUR_REACHED = '#22c55e';
const COLOUR_CURRENT = '#ff8904';
const COLOUR_UPCOMING = '#9ca3af';
const COLOUR_HALO = 'rgba(255, 255, 255, 0.9)';

const CURRENT_MARKER_SVG = `
  <svg viewBox="0 0 24 24" width="28" height="28" aria-hidden="true">
    <path fill="currentColor" d="M12 2a7 7 0 0 0-7 7c0 5.25 7 13 7 13s7-7.75 7-13a7 7 0 0 0-7-7z"/>
    <circle cx="12" cy="9" r="2.5" fill="#fff"/>
  </svg>`;

/**
 * MapLibre map for one trip's detail pane.
 *
 * <p>Flow sets the {@code stops} property with a compact JSON array, one
 * {@code [lat, lon, name, state]} tuple per stop. The element draws a line through the stops and a
 * dot per stop, and puts a pin on the stop the trip has currently reached. It fits the view to the
 * trip on every update, because the pane is small and the trip is the only subject on the map.
 */
class TripMap extends HTMLElement {
  private map?: MapLibreMap;
  private stopData: TripStop[] = [];
  private currentMarker?: Marker;
  private resizeObserver?: ResizeObserver;
  private themeObserver?: MutationObserver;
  private basemapFailures = 0;
  private usingFallback = false;
  private styleLoaded = false;
  private fallbackTimer?: number;
  /** The current stops still have to be framed; applied once the container has a real size. */
  private fitPending = false;

  connectedCallback(): void {
    if (this.map) {
      return;
    }
    const map = new MapLibreMap({
      container: this,
      style: styleFor(),
      center: DEFAULT_CENTER,
      zoom: DEFAULT_ZOOM,
      attributionControl: { compact: true },
    });
    this.map = map;
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');

    map.on('style.load', () => {
      this.styleLoaded = true;
      window.clearTimeout(this.fallbackTimer);
      this.updateSource();
    });
    map.on('error', (event) => this.onMapError(event));
    this.fallbackTimer = window.setTimeout(() => {
      if (!this.styleLoaded) {
        this.useFallback('the basemap style did not load in time');
      }
    }, STYLE_LOAD_TIMEOUT_MS);

    this.onStopClick();

    this.resizeObserver = new ResizeObserver(() => {
      // The pane is hidden while a trip loads, so the container can be 0x0 when the stops first
      // arrive. Resizing the canvas to 0x0 and back flashes it; skip until there is real space,
      // then frame the route instead of fitting a zero-sized viewport.
      if (this.clientWidth === 0 || this.clientHeight === 0) {
        return;
      }
      map.resize();
      this.applyFit();
    });
    this.resizeObserver.observe(this);

    this.themeObserver = new MutationObserver(() => {
      if (!this.usingFallback) {
        this.styleLoaded = false;
        map.setStyle(styleFor());
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
    this.currentMarker?.remove();
    this.currentMarker = undefined;
    this.map?.remove();
    this.map = undefined;
    this.styleLoaded = false;
  }

  /** Replaces the drawn stops; assigned by Flow as a JSON string. */
  set stops(json: string) {
    try {
      this.stopData = JSON.parse(json || '[]') as TripStop[];
    } catch {
      this.stopData = [];
    }
    this.updateSource();
  }

  private onMapError(event: unknown): void {
    const failure = event as { error?: Error; sourceId?: string };
    console.warn('[trip-map]', failure.error ?? event);
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
    this.styleLoaded = false;
    console.warn(`[trip-map] ${reason}; switching to raster OpenStreetMap tiles`);
    map.setStyle(rasterFallbackStyle() as never);
  }

  private positionedStops(): TripStop[] {
    return this.stopData.filter((stop) => Number.isFinite(stop[0]) && Number.isFinite(stop[1]));
  }

  private installLayers(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    if (!map.getSource(ROUTE_SOURCE)) {
      map.addSource(ROUTE_SOURCE, { type: 'geojson', data: this.routeFeature() });
      map.addLayer({
        id: ROUTE_LAYER,
        type: 'line',
        source: ROUTE_SOURCE,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOUR_UPCOMING,
          'line-width': 3,
          'line-opacity': 0.8,
        },
      });
    }
    if (!map.getSource(ROUTE_DONE_SOURCE)) {
      map.addSource(ROUTE_DONE_SOURCE, { type: 'geojson', data: this.routeDoneFeature() });
      map.addLayer({
        id: ROUTE_DONE_LAYER,
        type: 'line',
        source: ROUTE_DONE_SOURCE,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOUR_CURRENT,
          'line-width': 4,
        },
      });
    }
    if (!map.getSource(STOPS_SOURCE)) {
      map.addSource(STOPS_SOURCE, { type: 'geojson', data: this.stopFeatures() });
      map.addLayer({
        id: STOP_LAYER,
        type: 'circle',
        source: STOPS_SOURCE,
        paint: {
          'circle-color': [
            'match',
            ['get', 'state'],
            STATE_REACHED,
            COLOUR_REACHED,
            STATE_CURRENT,
            COLOUR_CURRENT,
            COLOUR_UPCOMING,
          ],
          'circle-radius': ['match', ['get', 'state'], STATE_CURRENT, 7, STATE_REACHED, 5, 4],
          'circle-stroke-width': 2,
          'circle-stroke-color': COLOUR_HALO,
        },
      });
    }
  }

  private updateSource(): void {
    const map = this.map;
    // Our own flag, not map.isStyleLoaded(): during the style.load event the map can still report
    // an unloaded style, which would skip installing the sources for good on a first render.
    if (!map || !this.styleLoaded) {
      return;
    }
    this.installLayers();
    (map.getSource(ROUTE_SOURCE) as GeoJSONSource | undefined)?.setData(this.routeFeature());
    (map.getSource(ROUTE_DONE_SOURCE) as GeoJSONSource | undefined)?.setData(this.routeDoneFeature());
    (map.getSource(STOPS_SOURCE) as GeoJSONSource | undefined)?.setData(this.stopFeatures());
    this.updateCurrentMarker();
    this.fitStops();
  }

  private routeFeature(): Feature<LineString> | FeatureCollection {
    const coordinates = this.positionedStops().map((stop) => [stop[1], stop[0]] as [number, number]);
    if (coordinates.length < 2) {
      return { type: 'FeatureCollection', features: [] };
    }
    return {
      type: 'Feature',
      geometry: { type: 'LineString', coordinates },
      properties: {},
    };
  }

  /** The part of the path up to the current stop, so progress is visible at a glance. */
  private routeDoneFeature(): Feature<LineString> | FeatureCollection {
    const stops = this.positionedStops();
    const current = stops.findIndex((stop) => stop[3] === STATE_CURRENT);
    const done = (current >= 0 ? stops.slice(0, current + 1) : stops.filter((stop) => stop[3] === STATE_REACHED)).map(
      (stop) => [stop[1], stop[0]] as [number, number],
    );
    if (done.length < 2) {
      return { type: 'FeatureCollection', features: [] };
    }
    return {
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: done },
      properties: {},
    };
  }

  private stopFeatures(): FeatureCollection<Point> {
    return {
      type: 'FeatureCollection',
      features: this.positionedStops().map((stop) => ({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [stop[1], stop[0]] },
        properties: { name: stop[2], state: stop[3] },
      })),
    };
  }

  /** A DOM marker with a pin, because the current stop deserves more than a circle. */
  private updateCurrentMarker(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    this.currentMarker?.remove();
    this.currentMarker = undefined;

    const current = this.positionedStops().find((stop) => stop[3] === STATE_CURRENT);
    if (!current) {
      return;
    }
    const element = document.createElement('div');
    element.className = 'trip-map-current-marker';
    element.innerHTML = CURRENT_MARKER_SVG;
    element.setAttribute('aria-label', current[2]);
    element.title = current[2];

    this.currentMarker = new Marker({ element, anchor: 'bottom' })
      .setLngLat([current[1], current[0]])
      .addTo(map);
  }

  /**
   * Requests a fit to the current stops.
   *
   * <p>The trip map is reused across trips and its frame is hidden while the stop times load, so a
   * fit can arrive while the container is still unlaid-out. It is remembered instead of applied so
   * the ResizeObserver can frame the route once the pane is visible; otherwise MapLibre would fit a
   * 0x0 viewport and open the new route at a nonsensical zoom.
   */
  private fitStops(): void {
    if (!this.map || !this.styleLoaded) {
      return;
    }
    this.fitPending = this.positionedStops().length > 0;
    this.applyFit();
  }

  private applyFit(): void {
    const map = this.map;
    if (!map || !this.styleLoaded || !this.fitPending) {
      return;
    }
    // display:none (the frame is hidden while loading) reports a zero-sized container.
    if (this.clientWidth === 0 || this.clientHeight === 0) {
      return;
    }
    const stops = this.positionedStops();
    if (stops.length === 0) {
      this.fitPending = false;
      return;
    }
    this.fitPending = false;
    // The ResizeObserver resizes the canvas before calling this, including when a hidden frame
    // becomes visible, so a second resize here would only make the canvas flicker with the fit.
    if (stops.length === 1) {
      map.jumpTo({ center: [stops[0][1], stops[0][0]], zoom: 14 });
      return;
    }
    const bounds = new LngLatBounds();
    stops.forEach((stop) => bounds.extend([stop[1], stop[0]]));
    map.fitBounds(bounds, { padding: 32, maxZoom: 15, duration: 0 });
  }

  private onStopClick(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    map.on('click', (event) => {
      if (!map.getLayer(STOP_LAYER)) {
        return;
      }
      const feature = map.queryRenderedFeatures(event.point, { layers: [STOP_LAYER] })[0];
      if (!feature) {
        return;
      }
      const name = String(feature.properties?.name ?? '');
      if (!name) {
        return;
      }
      const wrapper = document.createElement('div');
      wrapper.className = 'live-map-popup';
      const title = document.createElement('strong');
      title.textContent = name;
      wrapper.appendChild(title);
      new Popup({ offset: popupOffset(), closeButton: false })
        .setLngLat((feature.geometry as unknown as { coordinates: [number, number] }).coordinates)
        .setDOMContent(wrapper)
        .addTo(map);
    });
    map.on('mouseenter', STOP_LAYER, () => {
      map.getCanvas().style.cursor = 'pointer';
    });
    map.on('mouseleave', STOP_LAYER, () => {
      map.getCanvas().style.cursor = '';
    });
  }
}

customElements.define('trip-map', TripMap);
