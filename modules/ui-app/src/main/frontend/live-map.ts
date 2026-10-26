import maplibregl, { LngLatBounds, Map as MapLibreMap, Marker, Popup } from 'maplibre-gl';
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
const STYLE_LIGHT = 'https://tiles.openfreemap.org/styles/liberty';
const STYLE_DARK = 'https://tiles.openfreemap.org/styles/dark';

/** Popup offset in the map's pixel space, derived from the root font size rather than hard-coded. */
function popupOffset(): number {
  const rootFontSize = parseFloat(getComputedStyle(document.documentElement).fontSize);
  return Number.isFinite(rootFontSize) ? rootFontSize : 16;
}

function activeStyle(): string {
  const theme = document.documentElement.getAttribute('theme') ?? '';
  return theme.split(' ').includes('dark') ? STYLE_DARK : STYLE_LIGHT;
}

const DEFAULT_CENTER: [number, number] = [10.45, 51.16];
const DEFAULT_ZOOM = 5;

/**
 * MapLibre wrapper for the live updates view.
 *
 * <p>Flow sets the {@code markers} property with a JSON array on every poll. The map itself is
 * created once; a poll only replaces markers, and the view keeps the user's pan/zoom unless they
 * have not touched the map yet.
 */
class LiveMap extends HTMLElement {
  private map?: MapLibreMap;
  private markerData: LiveMapMarker[] = [];
  private markers: Marker[] = [];
  private resizeObserver?: ResizeObserver;
  private themeObserver?: MutationObserver;
  private followMarkers = true;

  connectedCallback(): void {
    if (this.map) {
      return;
    }
    this.map = new maplibregl.Map({
      container: this,
      style: activeStyle(),
      center: DEFAULT_CENTER,
      zoom: DEFAULT_ZOOM,
      attributionControl: { compact: true },
    });
    this.map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');

    // Stop following the markers once the user pans or zooms; a poll must not yank the view back.
    this.map.on('dragstart', () => {
      this.followMarkers = false;
    });
    this.map.on('zoomstart', (event) => {
      if (event.originalEvent) {
        this.followMarkers = false;
      }
    });

    this.resizeObserver = new ResizeObserver(() => this.map?.resize());
    this.resizeObserver.observe(this);

    // Vaadin flips the `theme` attribute on <html> when the user switches colour scheme.
    this.themeObserver = new MutationObserver(() => {
      this.map?.setStyle(activeStyle());
    });
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
    this.markers = [];
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
    if (!this.map) {
      return;
    }
    this.markers.forEach((marker) => marker.remove());
    this.markers = [];
    this.markerData
      .filter((marker) => Number.isFinite(marker.lat) && Number.isFinite(marker.lon))
      .forEach((marker) => {
        const element = document.createElement('div');
        element.className = this.markerClass(marker);
        element.title = marker.label;
        const popup = new Popup({ offset: popupOffset(), closeButton: false }).setDOMContent(
          this.popupContent(marker),
        );
        this.markers.push(
          new Marker({ element })
            .setLngLat([marker.lon, marker.lat])
            .setPopup(popup)
            .addTo(this.map!),
        );
      });
    this.fitMarkers();
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
  private fitMarkers(): void {
    if (!this.map || !this.followMarkers || this.markers.length === 0) {
      return;
    }
    if (this.markers.length === 1) {
      const only = this.markerData[0];
      this.map.jumpTo({ center: [only.lon, only.lat], zoom: 12 });
      return;
    }
    const bounds = new LngLatBounds();
    this.markerData.forEach((marker) => bounds.extend([marker.lon, marker.lat]));
    this.map.fitBounds(bounds, { padding: 48, maxZoom: 12, duration: 0 });
  }
}

customElements.define('live-map', LiveMap);
