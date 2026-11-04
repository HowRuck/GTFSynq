import { LngLatBounds, Map as MapLibreMap, NavigationControl } from 'maplibre-gl';
import {
  BASEMAP_SOURCE_IDS,
  FALLBACK_AFTER_ERRORS,
  STYLE_LOAD_TIMEOUT_MS,
  rasterFallbackStyle,
  styleFor,
} from './map-basemap';

/** View used before the subject has been framed. */
const DEFAULT_CENTER: [number, number] = [10.45, 51.16];
const DEFAULT_ZOOM = 5;

/** How the view frames the subject: zoom for a single position, padding and maxZoom for many. */
export interface FitOptions {
  singleSubjectZoom: number;
  padding: number;
  maxZoom: number;
}

/**
 * Parses a JSON payload pushed by Flow. JSON.parse returns any, so callers narrow the result
 * through a guard instead of a cast; a malformed payload is logged and treated as empty rather
 * than silently dropped.
 */
export function parseJsonPayload(json: string, elementName: string): unknown {
  try {
    return JSON.parse(json || '[]');
  } catch (error) {
    console.warn(`[${elementName}] ignoring malformed payload`, error);
    return [];
  }
}

/**
 * Parses a JSON array pushed by Flow, keeping only entries the guard accepts.
 */
export function parseJsonArray<T>(
  json: string,
  isEntry: (value: unknown) => value is T,
  elementName: string,
): T[] {
  const parsed = parseJsonPayload(json, elementName);
  if (!Array.isArray(parsed)) {
    return [];
  }
  return parsed.filter(isEntry);
}

/**
 * Shared lifecycle for the map custom elements: creates the MapLibre map, keeps the basemap style
 * in sync with the light/dark theme, falls back to raster tiles when the vector basemap fails,
 * and owns the resize loop. Subclasses install their data layers and provide the subject to frame.
 */
export abstract class BasemapElement extends HTMLElement {
  protected map?: MapLibreMap;
  protected styleLoaded = false;
  private resizeObserver?: ResizeObserver;
  private themeObserver?: MutationObserver;
  private basemapFailures = 0;
  private usingFallback = false;
  private fallbackTimer?: number;
  private customStyleUrl?: string;
  /** The current subject still has to be framed; applied once the container has a real size. */
  private fitPending = false;
  /** Whether the view was framed at least once; containment skips only apply afterwards. */
  private hasFitted = false;

  /** Positions ([lat, lon]) of the current subject, used to frame the view. */
  protected abstract subjectPositions(): [number, number][];
  /** How the view frames the subject. */
  protected abstract fitOptions(): FitOptions;
  /** Called when the basemap style (re)loaded, to (re)install sources and layers. */
  protected abstract onStyleLoaded(): void;
  /** Extra map listeners (clicks, follow behaviour); called once after the map is created. */
  protected onMapCreated(map: MapLibreMap): void {}
  /** Extra cleanup before the map is removed. */
  protected onDisconnect(): void {}
  /** Whether the view should follow the subject; the live map stops following on user pan. */
  protected shouldFollowSubject(): boolean {
    return true;
  }

  connectedCallback(): void {
    if (this.map) {
      return;
    }
    const map = this.createMap();
    this.map = map;

    this.observeStyleAndErrors(map);
    this.observeResize(map);
    this.observeTheme(map);
    this.onMapCreated(map);
  }

  disconnectedCallback(): void {
    window.clearTimeout(this.fallbackTimer);

    this.resizeObserver?.disconnect();
    this.resizeObserver = undefined;
    this.themeObserver?.disconnect();
    this.themeObserver = undefined;

    this.onDisconnect();
    this.map?.remove();
    this.map = undefined;
    this.styleLoaded = false;
    this.hasFitted = false;
  }

  /** Overrides the basemap style, for example to point at a self-hosted tile server. */
  set styleUrl(url: string) {
    this.customStyleUrl = url || undefined;

    if (this.map && !this.usingFallback) {
      this.styleLoaded = false;
      this.map.setStyle(this.currentStyle());
    }
  }

  /**
   * Requests a fit to the current subject.
   *
   * <p>Safe to call before the map is laid out: a container without a usable size leaves the fit
   * pending, and the ResizeObserver applies it once the map has real space. Unlike a fit on every
   * resize, this keeps a splitter drag from re-zooming the map continuously.
   */
  protected requestFit(): void {
    this.fitPending = this.subjectPositions().length > 0;
    this.applyFit();
  }

  protected currentStyle(): string {
    return styleFor(this.customStyleUrl);
  }

  private createMap(): MapLibreMap {
    const map = new MapLibreMap({
      container: this,
      style: this.currentStyle(),
      center: DEFAULT_CENTER,
      zoom: DEFAULT_ZOOM,
      attributionControl: { compact: true },
      // The element owns the resize loop below. MapLibre's own tracker throttles at 50 ms and
      // would resize the same container a second time, so a splitter drag would pay for two
      // canvas reallocations per movement.
      trackResize: false,
    });
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    return map;
  }

  private observeStyleAndErrors(map: MapLibreMap): void {
    // Fires on the first load and again after every setStyle, so the sources and layers survive a
    // light/dark switch.
    map.on('style.load', () => {
      this.styleLoaded = true;
      window.clearTimeout(this.fallbackTimer);
      this.onStyleLoaded();
    });

    // A basemap that cannot be fetched leaves an empty map with no explanation; log the failure and
    // switch to raster tiles instead of staying blank.
    map.on('error', (event) => this.onMapError(event));

    this.fallbackTimer = window.setTimeout(() => {
      if (!this.styleLoaded) {
        this.useFallback('the basemap style did not load in time');
      }
    }, STYLE_LOAD_TIMEOUT_MS);
  }

  private observeResize(map: MapLibreMap): void {
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

      // resize() reallocates the drawing buffer, which clears the canvas. Without an immediate
      // redraw the cleared frame can reach the screen during a splitter drag, which is the flash.
      map.redraw();
    });
    this.resizeObserver.observe(this);
  }

  private observeTheme(map: MapLibreMap): void {
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

  private onMapError(event: unknown): void {
    const failure = event as { error?: Error; sourceId?: string };
    console.warn(`[${this.tagName.toLowerCase()}]`, failure.error ?? event);

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

    console.warn(`[${this.tagName.toLowerCase()}] ${reason}; switching to raster OpenStreetMap tiles`);
    map.setStyle(rasterFallbackStyle());
  }

  private applyFit(): void {
    const map = this.map;
    if (!map || !this.styleLoaded || !this.shouldFollowSubject() || !this.fitPending) {
      return;
    }
    // display:none (the frame is hidden while loading) reports a zero-sized container.
    if (this.clientWidth === 0 || this.clientHeight === 0) {
      return;
    }
    const positions = this.subjectPositions();
    if (positions.length === 0) {
      this.fitPending = false;
      return;
    }

    this.fitPending = false;
    const options = this.fitOptions();

    if (positions.length === 1) {
      const center: [number, number] = [positions[0][1], positions[0][0]];
      // After the first framing, a poll that leaves the subject in view must not move the camera:
      // every jumpTo is a camera move and a full re-render.
      if (this.hasFitted && map.getBounds().contains(center)) {
        return;
      }
      this.hasFitted = true;
      map.jumpTo({ center, zoom: options.singleSubjectZoom });
      return;
    }

    const bounds = new LngLatBounds();
    positions.forEach((position) => bounds.extend([position[1], position[0]]));

    // While following, every changed poll re-fits even when the current view already contains all
    // markers. Skip the fit then: each fitBounds is a camera move and a full re-render. The corner
    // check fails for bounds crossing the antimeridian, which then simply fits as before.
    const view = map.getBounds();
    if (this.hasFitted && view.contains(bounds.getSouthWest()) && view.contains(bounds.getNorthEast())) {
      return;
    }

    this.hasFitted = true;
    map.fitBounds(bounds, { padding: options.padding, maxZoom: options.maxZoom, duration: 0 });
  }
}
