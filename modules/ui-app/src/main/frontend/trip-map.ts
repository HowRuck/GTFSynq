import { Map as MapLibreMap, Marker, Popup } from 'maplibre-gl';
import type { GeoJSONSource, PointLike } from 'maplibre-gl';
import type { Feature, FeatureCollection, LineString, Point } from 'geojson';
import { BasemapElement, parseJsonArray, type FitOptions } from './basemap-element';
import { pointCoordinates, popupOffset } from './map-basemap';

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

/** A line layer over a GeoJSON source: the route and the already-travelled part share this shape. */
interface RouteLayerSpec {
  sourceId: string;
  layerId: string;
  data: Feature<LineString> | FeatureCollection;
  color: string;
  width: number;
  opacity: number;
}

function isTripStop(value: unknown): value is TripStop {
  return (
    Array.isArray(value) &&
    value.length === 4 &&
    typeof value[0] === 'number' &&
    typeof value[1] === 'number' &&
    typeof value[2] === 'string' &&
    typeof value[3] === 'number'
  );
}

function stopLngLat(stop: TripStop): [number, number] {
  return [stop[1], stop[0]];
}

function lineFeature(coordinates: [number, number][]): Feature<LineString> | FeatureCollection {
  if (coordinates.length < 2) {
    return { type: 'FeatureCollection', features: [] };
  }
  return {
    type: 'Feature',
    geometry: { type: 'LineString', coordinates },
    properties: {},
  };
}

/**
 * MapLibre map for one trip's detail pane.
 *
 * <p>Flow sets the {@code stops} property with a compact JSON array, one
 * {@code [lat, lon, name, state]} tuple per stop. The element draws a line through the stops and a
 * dot per stop, and puts a pin on the stop the trip has currently reached. It fits the view to the
 * trip on every update, because the pane is small and the trip is the only subject on the map.
 */
class TripMap extends BasemapElement {
  private stopData: TripStop[] = [];
  private currentMarker?: Marker;

  protected subjectPositions(): [number, number][] {
    return this.positionedStops().map((stop): [number, number] => [stop[0], stop[1]]);
  }

  protected fitOptions(): FitOptions {
    return { singleSubjectZoom: 14, padding: 32, maxZoom: 15 };
  }

  protected onMapCreated(map: MapLibreMap): void {
    map.on('click', (event) => this.onMapClick(map, event.point));

    map.on('mouseenter', STOP_LAYER, () => {
      map.getCanvas().style.cursor = 'pointer';
    });
    map.on('mouseleave', STOP_LAYER, () => {
      map.getCanvas().style.cursor = '';
    });
  }

  protected onDisconnect(): void {
    this.currentMarker?.remove();
    this.currentMarker = undefined;
  }

  /** Replaces the drawn stops; assigned by Flow as a JSON string. */
  set stops(json: string) {
    this.stopData = parseJsonArray(json, isTripStop, 'trip-map');
    // Cleared while the next trip loads: that trip must be framed, not left in the previous view.
    if (this.positionedStops().length === 0) {
      this.forgetFraming();
    }
    this.updateSource();
  }

  protected onStyleLoaded(): void {
    this.updateSource();
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
    this.requestFit();
  }

  private positionedStops(): TripStop[] {
    return this.stopData.filter((stop) => Number.isFinite(stop[0]) && Number.isFinite(stop[1]));
  }

  private installLayers(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    this.installRouteLayer(map, {
      sourceId: ROUTE_SOURCE,
      layerId: ROUTE_LAYER,
      data: this.routeFeature(),
      color: COLOUR_UPCOMING,
      width: 3,
      opacity: 0.8,
    });
    this.installRouteLayer(map, {
      sourceId: ROUTE_DONE_SOURCE,
      layerId: ROUTE_DONE_LAYER,
      data: this.routeDoneFeature(),
      color: COLOUR_CURRENT,
      width: 4,
      opacity: 1,
    });

    this.installStopLayer(map);
  }

  private installRouteLayer(map: MapLibreMap, spec: RouteLayerSpec): void {
    if (map.getSource(spec.sourceId)) {
      return;
    }
    map.addSource(spec.sourceId, { type: 'geojson', data: spec.data });
    map.addLayer({
      id: spec.layerId,
      type: 'line',
      source: spec.sourceId,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': spec.color,
        'line-width': spec.width,
        'line-opacity': spec.opacity,
      },
    });
  }

  private installStopLayer(map: MapLibreMap): void {
    if (map.getSource(STOPS_SOURCE)) {
      return;
    }
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

  private routeFeature(): Feature<LineString> | FeatureCollection {
    return lineFeature(this.positionedStops().map(stopLngLat));
  }

  /** The part of the path up to the current stop, so progress is visible at a glance. */
  private routeDoneFeature(): Feature<LineString> | FeatureCollection {
    return lineFeature(this.doneStops().map(stopLngLat));
  }

  private doneStops(): TripStop[] {
    const stops = this.positionedStops();
    const current = stops.findIndex((stop) => stop[3] === STATE_CURRENT);

    if (current >= 0) {
      return stops.slice(0, current + 1);
    }
    return stops.filter((stop) => stop[3] === STATE_REACHED);
  }

  private stopFeatures(): FeatureCollection<Point> {
    return {
      type: 'FeatureCollection',
      features: this.positionedStops().map((stop) => ({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: stopLngLat(stop) },
        properties: { name: stop[2], state: stop[3] },
      })),
    };
  }

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

    this.currentMarker = new Marker({ element: this.currentMarkerElement(current), anchor: 'bottom' })
      .setLngLat(stopLngLat(current))
      .addTo(map);
  }

  /** A DOM marker with a pin, because the current stop deserves more than a circle. */
  private currentMarkerElement(stop: TripStop): HTMLElement {
    const element = document.createElement('div');
    element.className = 'trip-map-current-marker';
    element.innerHTML = CURRENT_MARKER_SVG;
    element.setAttribute('aria-label', stop[2]);
    element.title = stop[2];
    return element;
  }

  private onMapClick(map: MapLibreMap, point: PointLike): void {
    if (!map.getLayer(STOP_LAYER)) {
      return;
    }
    const feature = map.queryRenderedFeatures(point, { layers: [STOP_LAYER] })[0];
    if (!feature) {
      return;
    }
    const name = String(feature.properties?.name ?? '');
    if (!name) {
      return;
    }
    const coordinates = pointCoordinates(feature.geometry);
    if (!coordinates) {
      return;
    }

    new Popup({ offset: popupOffset(), closeButton: false })
      .setLngLat(coordinates)
      .setDOMContent(this.stopPopup(name))
      .addTo(map);
  }

  private stopPopup(name: string): HTMLElement {
    const wrapper = document.createElement('div');
    wrapper.className = 'live-map-popup';
    const title = document.createElement('strong');
    title.textContent = name;
    wrapper.appendChild(title);
    return wrapper;
  }
}

customElements.define('trip-map', TripMap);
