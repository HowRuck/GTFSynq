import { Map as MapLibreMap, Popup } from 'maplibre-gl';
import type { GeoJSONSource, PointLike } from 'maplibre-gl';
import { BasemapElement, parseJsonArray, type FitOptions } from './basemap-element';
import { pointCoordinates, popupOffset } from './map-basemap';

/**
 * One trip position as a compact tuple:
 * [lat, lon, label, detail, delaySeconds, reachedMinutesAgo, tripUpdateId].
 *
 * <p>The id is a string because a 64-bit id can exceed JavaScript's safe integer range.
 *
 * <p>The tuple form replaces a keyed object because the marker array is pushed to the browser on
 * every poll; repeating the keys per marker costs more than the values themselves at scale.
 */
type LiveMapMarker = [number, number, string, string, number | null, number | null, string];

interface MarkerProperties {
  label: string;
  detail: string;
  delayClass: 'early' | 'on-time' | 'late' | 'severe' | 'unknown';
  reachedMinutesAgo: number | null;
  tripUpdateId: string;
}

interface MarkerFeature {
  type: 'Feature';
  geometry: { type: 'Point'; coordinates: [number, number] };
  properties: MarkerProperties;
}

interface MarkerFeatureCollection {
  type: 'FeatureCollection';
  features: MarkerFeature[];
}

const SOURCE_ID = 'live-trips';
const CLUSTER_LAYER = 'live-trips-clusters';
const CLUSTER_COUNT_LAYER = 'live-trips-cluster-count';
const POINT_LAYER = 'live-trips-points';

/** Optional raster overlay blended over the vector basemap (e.g. Thunderforest Transport). */
const TRANSPORT_SOURCE = 'transport-overlay';
const TRANSPORT_LAYER = 'transport-overlay';
const TRANSPORT_ATTRIBUTION = '© OpenStreetMap contributors, Thunderforest';

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

/** Longest popup label shown before it is elided; matches the table's id limit. */
const MAX_POPUP_LABEL_LENGTH = 32;

function isLiveMapMarker(value: unknown): value is LiveMapMarker {
  if (!Array.isArray(value) || value.length !== 7) {
    return false;
  }
  const [lat, lon, label, detail, delay, reachedMinutesAgo, tripUpdateId] = value;
  return (
    typeof lat === 'number' &&
    typeof lon === 'number' &&
    typeof label === 'string' &&
    typeof detail === 'string' &&
    (delay === null || typeof delay === 'number') &&
    (reachedMinutesAgo === null || typeof reachedMinutesAgo === 'number') &&
    typeof tripUpdateId === 'string'
  );
}

function elideLabel(value: string): string {
  return value.length <= MAX_POPUP_LABEL_LENGTH
    ? value
    : `${value.slice(0, MAX_POPUP_LABEL_LENGTH - 1)}…`;
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

function markerFeature(marker: LiveMapMarker): MarkerFeature {
  return {
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [marker[1], marker[0]] },
    properties: {
      label: marker[2],
      detail: marker[3],
      delayClass: delayClass(marker[4]),
      reachedMinutesAgo: marker[5],
      tripUpdateId: marker[6],
    },
  };
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
class LiveMap extends BasemapElement {
  private markerData: LiveMapMarker[] = [];
  private followMarkers = true;
  private markersAt = Date.now();
  private reachedJustNowText = 'Reached just now';
  private reachedMinutesFormat = 'Reached {0} min ago';
  private openTripLabel = 'Open trip';
  private transportUrl?: string;
  private transportOpacity = 0.55;

  protected subjectPositions(): [number, number][] {
    return this.positionedMarkers().map((marker): [number, number] => [marker[0], marker[1]]);
  }

  protected fitOptions(): FitOptions {
    return { singleSubjectZoom: 12, padding: 48, maxZoom: 12 };
  }

  protected shouldFollowSubject(): boolean {
    return this.followMarkers;
  }

  protected onMapCreated(map: MapLibreMap): void {
    // Stop following the markers once the user pans or zooms; a poll must not yank the view back.
    map.on('dragstart', () => {
      this.followMarkers = false;
    });

    map.on('zoomstart', (event) => {
      if ((event as { originalEvent?: Event }).originalEvent) {
        this.followMarkers = false;
      }
    });

    map.on('click', (event) => this.onMapClick(map, event.point));
    this.registerHoverCursor(map);
  }

  protected onStyleLoaded(): void {
    this.updateSource();
  }

  /** Replaces the markers; assigned by Flow as a JSON string. */
  set markers(json: string) {
    this.markerData = parseJsonArray(json, isLiveMapMarker, 'live-map');
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

  /** Localized action label that opens the trip's detail pane. */
  set openTripText(value: string) {
    this.openTripLabel = value || 'Open trip';
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

  private updateSource(): void {
    const map = this.map;
    // Our own flag, not map.isStyleLoaded(): during the style.load event the map can still report
    // an unloaded style, which would skip installing the sources for good on a first render.
    if (!map || !this.styleLoaded) {
      return;
    }
    this.syncTransportOverlay();
    this.installLayers();

    (map.getSource(SOURCE_ID) as GeoJSONSource | undefined)?.setData(this.featureCollection());
    this.requestFit();
  }

  private installLayers(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    this.ensureMarkerSource(map);
    this.installClusterLayers(map);

    this.installPointLayer(map);
  }

  private ensureMarkerSource(map: MapLibreMap): void {
    if (map.getSource(SOURCE_ID)) {
      return;
    }
    map.addSource(SOURCE_ID, {
      type: 'geojson',
      data: this.featureCollection(),
      cluster: true,
      clusterMaxZoom: CLUSTER_MAX_ZOOM,
      clusterRadius: CLUSTER_RADIUS,
    });
  }

  private installClusterLayers(map: MapLibreMap): void {
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
  }

  private installPointLayer(map: MapLibreMap): void {
    if (map.getLayer(POINT_LAYER)) {
      return;
    }
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

  /**
   * Adds or removes the optional raster basemap overlay. It is inserted below the marker layers so
   * clusters and points stay readable on top of it.
   */
  private syncTransportOverlay(): void {
    const map = this.map;
    if (!map || !this.styleLoaded) {
      return;
    }
    const url = this.transportUrl;
    if (!url) {
      this.removeTransportOverlay(map);
      return;
    }

    this.addTransportOverlay(map, url);
  }

  private removeTransportOverlay(map: MapLibreMap): void {
    if (map.getLayer(TRANSPORT_LAYER)) {
      map.removeLayer(TRANSPORT_LAYER);
    }
    if (map.getSource(TRANSPORT_SOURCE)) {
      map.removeSource(TRANSPORT_SOURCE);
    }
  }

  private addTransportOverlay(map: MapLibreMap, url: string): void {
    if (!map.getSource(TRANSPORT_SOURCE)) {
      map.addSource(TRANSPORT_SOURCE, {
        type: 'raster',
        tiles: [url],
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
      return;
    }
    map.setPaintProperty(TRANSPORT_LAYER, 'raster-opacity', this.transportOpacity);
  }

  private featureCollection(): MarkerFeatureCollection {
    return {
      type: 'FeatureCollection',
      features: this.positionedMarkers().map(markerFeature),
    };
  }

  private positionedMarkers(): LiveMapMarker[] {
    return this.markerData.filter(
      (marker) => Number.isFinite(marker[0]) && Number.isFinite(marker[1]),
    );
  }

  /** Expands clusters on click and shows the trip details of a single point. */
  private onMapClick(map: MapLibreMap, point: PointLike): void {
    if (this.expandCluster(map, point)) {
      return;
    }
    this.openPointPopup(map, point);
  }

  /** Expands a cluster when the click hit one; returns whether it did. */
  private expandCluster(map: MapLibreMap, point: PointLike): boolean {
    if (!map.getLayer(CLUSTER_LAYER)) {
      return false;
    }
    const cluster = map.queryRenderedFeatures(point, { layers: [CLUSTER_LAYER] })[0];
    if (!cluster) {
      return false;
    }
    const clusterId = Number(cluster.properties?.cluster_id);
    const coordinates = pointCoordinates(cluster.geometry);
    const source = map.getSource(SOURCE_ID) as GeoJSONSource | undefined;

    if (coordinates) {
      source
        ?.getClusterExpansionZoom(clusterId)
        .then((zoom) => map.easeTo({ center: coordinates, zoom }))
        .catch(() => undefined);
    }
    return true;
  }

  private openPointPopup(map: MapLibreMap, point: PointLike): void {
    if (!map.getLayer(POINT_LAYER)) {
      return;
    }
    const feature = map.queryRenderedFeatures(point, { layers: [POINT_LAYER] })[0];
    if (!feature) {
      return;
    }
    const coordinates = pointCoordinates(feature.geometry);
    if (!coordinates) {
      return;
    }
    const properties = feature.properties as MarkerProperties;
    new Popup({ offset: popupOffset(), closeButton: false })
      .setLngLat(coordinates)
      .setDOMContent(this.popupContent(properties))
      .addTo(map);
  }

  /** Turns the pointer into a hand over anything clickable. */
  private registerHoverCursor(map: MapLibreMap): void {
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
    wrapper.appendChild(this.popupTitle(properties.label));

    if (properties.detail) {
      wrapper.appendChild(this.popupDetail(properties.detail));
    }

    if (typeof properties.reachedMinutesAgo === 'number') {
      wrapper.appendChild(this.popupReached(properties.reachedMinutesAgo));
    }

    if (properties.tripUpdateId && properties.tripUpdateId !== '0') {
      wrapper.appendChild(this.popupOpenButton(properties.tripUpdateId));
    }

    return wrapper;
  }

  private popupTitle(label: string): HTMLElement {
    const title = document.createElement('strong');
    title.textContent = elideLabel(label);
    // The elided id stays readable in full on hover; the popup must not grow to fit it.
    title.title = label;
    return title;
  }

  private popupDetail(detail: string): HTMLElement {
    const element = document.createElement('div');
    element.textContent = detail;
    return element;
  }

  private popupReached(reachedMinutesAgo: number): HTMLElement {
    // The popup ages the "reached N min ago" text from the moment this snapshot arrived.
    const minutes = reachedMinutesAgo + Math.floor((Date.now() - this.markersAt) / 60_000);

    const reached = document.createElement('div');
    reached.className = 'live-map-popup-reached';
    reached.textContent =
      minutes <= 0
        ? this.reachedJustNowText
        : this.reachedMinutesFormat.replace('{0}', String(minutes));
    return reached;
  }

  private popupOpenButton(tripUpdateId: string): HTMLElement {
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'live-map-popup-open';
    open.textContent = this.openTripLabel;

    open.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopPropagation();
      this.dispatchEvent(
        new CustomEvent('trip-open', {
          detail: tripUpdateId,
          bubbles: true,
          composed: true,
        }),
      );
    });
    return open;
  }
}

customElements.define('live-map', LiveMap);
