import { LngLatBounds, Map as MapLibreMap, NavigationControl, Popup } from 'maplibre-gl';
import type { GeoJSONSource } from 'maplibre-gl';
import {
  BASEMAP_SOURCE_IDS,
  FALLBACK_AFTER_ERRORS,
  STYLE_LOAD_TIMEOUT_MS,
  popupOffset,
  rasterFallbackStyle,
  styleFor,
} from './map-basemap';

/**
 * One trip position as a compact tuple: [lat, lon, label, detail, delaySeconds, reachedMinutesAgo].
 *
 * <p>The tuple form replaces a keyed object because the marker array is pushed to the browser on
 * every poll; repeating the keys per marker costs more than the values themselves at scale.
 */
type LiveMapMarker = [number, number, string, string, number | null, number | null];

interface MarkerProperties {
  label: string;
  detail: string;
  delayClass: 'early' | 'on-time' | 'late' | 'severe' | 'unknown';
  reachedMinutesAgo: number | null;
}

const SOURCE_ID = 'live-trips';
const CLUSTER_LAYER = 'live-trips-clusters';
const CLUSTER_COUNT_LAYER = 'live-trips-cluster-count';
const POINT_LAYER = 'live-trips-points';

/** Optional raster overlay blended over the vector basemap (e.g. Thunderforest Transport). */
const TRANSPORT_SOURCE = 'transport-overlay';
const TRANSPORT_LAYER = 'transport-overlay';
const TRANSPORT_ATTRIBUTION = '\u00a9 OpenStreetMap contributors, Thunderforest';

/**
 * Map-space tuning, in CSS-free map units: pixels below the cluster radius are merged. Clustering
 * stays on until street zoom: many trips share the same latest stop, and unclustering too early
 * would stack their markers on one pixel even though the cluster counted them separately.
 */
const CLUSTER_MAX_ZOOM = 16;
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
 * <p>Flow sets the {@code markers} property with a compact JSON array — one
 * {@code [lat, lon, label, detail, delay]} tuple per trip — on every poll. The array form keeps the
 * pushed payload small for a feed with tens of thousands of live trips, and Flow skips the push when
 * the snapshot did not change. The trips are held in a clustered GeoJSON source rather than as DOM
 * markers, so a city full of trips stays one readable cluster instead of overlapping pins; the map
 * itself is created once and a poll only replaces the source data.
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
  private markersAt = Date.now();
  /** The current markers still have to be framed; applied once the container has a real size. */
  private fitPending = false;
  private reachedJustNowText = 'Reached just now';
  private reachedMinutesFormat = 'Reached {0} min ago';
  private transportUrl?: string;
  private transportOpacity = 0.55;

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

    this.resizeObserver = new ResizeObserver(() => {
      // A splitter can report a zero-sized step mid-drag; resizing the canvas to 0x0 and back is
      // what makes it flash, and the next non-zero callback sizes it correctly anyway.
      if (this.clientWidth === 0 || this.clientHeight === 0) {
        return;
      }
      map.resize();
      // Resizing changes the viewport, not the subject: keep the user's current view. Only a fit
      // that could not be applied yet (for example while the container was still 0x0) is completed
      // here. Refitting on every splitter tick made the map jump and flicker while dragging.
      this.applyFit();
    });
    this.resizeObserver.observe(this);

    // Vaadin flips the `theme` attribute on <html> when the user switches colour scheme.
    this.themeObserver = new MutationObserver(() => {
      if (!this.usingFallback) {
        this.styleLoaded = false;
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
    this.styleLoaded = false;
  }

  /** Overrides the basemap style, for example to point at a self-hosted tile server. */
  set styleUrl(url: string) {
    this.customStyleUrl = url || undefined;
    if (this.map && !this.usingFallback) {
      this.styleLoaded = false;
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
    // The popup ages the "reached N min ago" text from the moment this snapshot arrived.
    this.markersAt = Date.now();
    this.updateSource();
  }

  /** Localized text for a stop reached less than a minute ago. */
  set reachedJustNow(value: string) {
    this.reachedJustNowText = value || 'Reached just now';
  }

  /** Localized text with a {0} placeholder for the elapsed minutes. */
  set reachedMinutes(value: string) {
    this.reachedMinutesFormat = value || 'Reached {0} min ago';
  }

  /** Optional raster tile URL; an empty value removes the overlay. */
  set transportOverlayUrl(url: string) {
    this.transportUrl = url || undefined;
    this.syncTransportOverlay();
  }

  /** Opacity of the transport overlay, clamped to 0..1. */
  set transportOverlayOpacity(value: number) {
    this.transportOpacity = Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0.55;
    const map = this.map;
    if (map?.getLayer(TRANSPORT_LAYER)) {
      map.setPaintProperty(TRANSPORT_LAYER, 'raster-opacity', this.transportOpacity);
    }
  }

  private currentStyle(): string {
    return styleFor(this.customStyleUrl);
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
    this.styleLoaded = false;
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
    // Our own flag, not map.isStyleLoaded(): during the style.load event the map can still report
    // an unloaded style, which would skip installing the sources for good on a first render.
    if (!map || !this.styleLoaded) {
      return;
    }
    this.syncTransportOverlay();
    this.installLayers();
    const source = map.getSource(SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(this.featureCollection());
    this.fitMarkers();
  }

  /**
   * Adds or removes the optional raster basemap overlay. It is inserted below the marker layers so
   * clusters and points stay readable on top of it.
   */
  private syncTransportOverlay(): void {
    const map = this.map;
    if (!map || !this.styleLoaded) {
      return;
    }
    if (!this.transportUrl) {
      if (map.getLayer(TRANSPORT_LAYER)) {
        map.removeLayer(TRANSPORT_LAYER);
      }
      if (map.getSource(TRANSPORT_SOURCE)) {
        map.removeSource(TRANSPORT_SOURCE);
      }
      return;
    }
    if (!map.getSource(TRANSPORT_SOURCE)) {
      map.addSource(TRANSPORT_SOURCE, {
        type: 'raster',
        tiles: [this.transportUrl],
        tileSize: 256,
        maxzoom: 19,
        attribution: TRANSPORT_ATTRIBUTION,
      });
    }
    if (!map.getLayer(TRANSPORT_LAYER)) {
      const before = map.getLayer(CLUSTER_LAYER) ? CLUSTER_LAYER : undefined;
      map.addLayer(
        {
          id: TRANSPORT_LAYER,
          type: 'raster',
          source: TRANSPORT_SOURCE,
          paint: { 'raster-opacity': this.transportOpacity },
        },
        before,
      );
    } else {
      map.setPaintProperty(TRANSPORT_LAYER, 'raster-opacity', this.transportOpacity);
    }
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
        .filter((marker) => Number.isFinite(marker[0]) && Number.isFinite(marker[1]))
        .map((marker) => ({
          type: 'Feature',
          geometry: { type: 'Point', coordinates: [marker[1], marker[0]] },
          properties: {
            label: marker[2],
            detail: marker[3],
            delayClass: delayClass(marker[4]),
            reachedMinutesAgo: marker[5],
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
          const coordinates = (cluster.geometry as unknown as { coordinates: [number, number] })
            .coordinates;
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
        .setLngLat((point.geometry as unknown as { coordinates: [number, number] }).coordinates)
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
    if (typeof properties.reachedMinutesAgo === 'number') {
      const minutes =
        properties.reachedMinutesAgo + Math.floor((Date.now() - this.markersAt) / 60_000);
      const reached = document.createElement('div');
      reached.className = 'live-map-popup-reached';
      reached.textContent =
        minutes <= 0
          ? this.reachedJustNowText
          : this.reachedMinutesFormat.replace('{0}', String(minutes));
      wrapper.appendChild(reached);
    }
    return wrapper;
  }

  /**
   * Requests a fit to the current markers while the user has not taken over the map.
   *
   * <p>Safe to call before the map is laid out: a container without a usable size leaves the fit
   * pending, and the ResizeObserver applies it once the map has real space. Unlike a fit on every
   * resize, this keeps a splitter drag from re-zooming the map continuously.
   */
  private fitMarkers(): void {
    this.fitPending = this.positionedMarkers().length > 0;
    this.applyFit();
  }

  private applyFit(): void {
    const map = this.map;
    if (!map || !this.styleLoaded || !this.followMarkers || !this.fitPending) {
      return;
    }
    if (this.clientWidth === 0 || this.clientHeight === 0) {
      return;
    }
    const positioned = this.positionedMarkers();
    if (positioned.length === 0) {
      this.fitPending = false;
      return;
    }
    this.fitPending = false;
    if (positioned.length === 1) {
      map.jumpTo({ center: [positioned[0][1], positioned[0][0]], zoom: 12 });
      return;
    }
    const bounds = new LngLatBounds();
    positioned.forEach((marker) => bounds.extend([marker[1], marker[0]]));
    map.fitBounds(bounds, { padding: 48, maxZoom: 12, duration: 0 });
  }

  private positionedMarkers(): LiveMapMarker[] {
    return this.markerData.filter(
      (marker) => Number.isFinite(marker[0]) && Number.isFinite(marker[1]),
    );
  }
}

customElements.define('live-map', LiveMap);
