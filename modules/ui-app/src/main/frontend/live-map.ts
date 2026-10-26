import { LngLatBounds, Map as MapLibreMap, Marker, NavigationControl, Popup } from 'maplibre-gl';
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

/**
 * OpenFreeMap vector styles: free, no API key, no usage limits, and the style carries its own
 * attribution. Positron is a muted basemap that keeps the delay-coloured markers readable.
 */
const STYLE_LIGHT = 'https://tiles.openfreemap.org/styles/positron';
const STYLE_DARK = 'https://tiles.openfreemap.org/styles/dark';

const DEFAULT_CENTER: [number, number] = [10.45, 51.16];
const DEFAULT_ZOOM = 5;

function activeStyle(): string {
  const theme = document.documentElement.getAttribute('theme') ?? '';
  return theme.split(' ').includes('dark') ? STYLE_DARK : STYLE_LIGHT;
}

/** Popup offset in the map's pixel space, derived from the root font size rather than hard-coded. */
function popupOffset(): number {
  const rootFontSize = parseFloat(getComputedStyle(document.documentElement).fontSize);
  return Number.isFinite(rootFontSize) ? rootFontSize : 16;
}

/**
 * MapLibre wrapper for the live updates view.
 *
 * <p>Flow sets the {@code markers} property with a JSON array on every poll. The map itself is
 * created once; a poll only replaces markers, and the view keeps the user's pan and zoom once they
 * have taken the map over.
 */
class LiveMap extends HTMLElement {
  private map?: MapLibreMap;
  private markerData: LiveMapMarker[] = [];
  private markerObjects: Marker[] = [];
  private resizeObserver?: ResizeObserver;
  private themeObserver?: MutationObserver;
  private followMarkers = true;

  connectedCallback(): void {
    if (this.map) {
      return;
    }
    const map = new MapLibreMap({
      container: this,
      style: activeStyle(),
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

    this.resizeObserver = new ResizeObserver(() => map.resize());
    this.resizeObserver.observe(this);

    // Vaadin flips the `theme` attribute on <html> when the user switches colour scheme.
    this.themeObserver = new MutationObserver(() => map.setStyle(activeStyle()));
    this.themeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['theme'],
    });

    this.renderMarkers();
  }

  disconnectedCallback(): void {
    this.resizeObserver?.disconnect();
    this.resizeObserver = undefined;
    this.themeObserver?.disconnect();
    this.themeObserver = undefined;
    this.markerObjects = [];
    this.map?.remove();
    this.map = undefined;
  }

  /** Replaces the markers; assigned by Flow as a JSON string. */
  set markers(json: string) {
    try {
      this.markerData = JSON.parse(json || '[]') as LiveMapMarker[];
    } catch {
      this.markerData = [];
    }
    this.renderMarkers();
  }

  private renderMarkers(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    this.markerObjects.forEach((marker) => marker.remove());
    const visible = this.markerData.filter(
      (marker) => Number.isFinite(marker.lat) && Number.isFinite(marker.lon),
    );
    this.markerObjects = visible.map((marker) => {
      const element = document.createElement('div');
      element.className = this.markerClass(marker);
      element.title = marker.label;
      const popup = new Popup({ offset: popupOffset(), closeButton: false }).setDOMContent(
        this.popupContent(marker),
      );
      return new Marker({ element }).setLngLat([marker.lon, marker.lat]).setPopup(popup).addTo(map);
    });
    this.fitMarkers(visible);
  }

  /** Mirrors the delay colouring of the grid's delay cells. */
  private markerClass(marker: LiveMapMarker): string {
    const delay = marker.delaySeconds;
    if (delay === null) {
      return 'live-map-dot';
    }
    if (delay > 300) {
      return 'live-map-dot severe';
    }
    if (delay > 60) {
      return 'live-map-dot late';
    }
    if (delay < -60) {
      return 'live-map-dot early';
    }
    return 'live-map-dot on-time';
  }

  private popupContent(marker: LiveMapMarker): HTMLElement {
    const wrapper = document.createElement('div');
    wrapper.className = 'live-map-popup';
    const title = document.createElement('strong');
    title.textContent = marker.label;
    wrapper.appendChild(title);
    if (marker.detail) {
      const detail = document.createElement('div');
      detail.textContent = marker.detail;
      wrapper.appendChild(detail);
    }
    return wrapper;
  }

  /** Fits the view to the markers while the user has not taken over the map. */
  private fitMarkers(visible: LiveMapMarker[]): void {
    const map = this.map;
    if (!map || !this.followMarkers || visible.length === 0) {
      return;
    }
    if (!map.loaded()) {
      // The container has no usable size before the first style load, so fit once it does.
      map.once('load', () => this.fitMarkers(visible));
      return;
    }
    if (visible.length === 1) {
      map.jumpTo({ center: [visible[0].lon, visible[0].lat], zoom: 12 });
      return;
    }
    const bounds = new LngLatBounds();
    visible.forEach((marker) => bounds.extend([marker.lon, marker.lat]));
    map.fitBounds(bounds, { padding: 48, maxZoom: 12, duration: 0 });
  }
}

customElements.define('live-map', LiveMap);
