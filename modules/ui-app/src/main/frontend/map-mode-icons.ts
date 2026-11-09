import type { Map as MapLibreMap } from 'maplibre-gl';
import aerialLiftIcon from './icons/transit-aerial-lift.svg?raw';
import busIcon from './icons/transit-bus.svg?raw';
import ferryIcon from './icons/transit-ferry.svg?raw';
import funicularIcon from './icons/transit-funicular.svg?raw';
import genericIcon from './icons/transit-generic.svg?raw';
import monorailIcon from './icons/transit-monorail.svg?raw';
import railIcon from './icons/transit-rail.svg?raw';
import subwayIcon from './icons/transit-subway.svg?raw';
import tramIcon from './icons/transit-tram.svg?raw';

/**
 * The transit modes the live map draws a glyph for.
 *
 * <p>Cable trams share the tram glyph and trolleybuses share the bus glyph: the vehicles are the
 * same shape, and a map marker is too small to show how they are powered.
 */
export type TransitMode =
  | 'bus'
  | 'tram'
  | 'rail'
  | 'subway'
  | 'ferry'
  | 'monorail'
  | 'aerial-lift'
  | 'funicular'
  | 'generic';

/**
 * GTFS base route types. Extended types (100+) are ranges, so they are matched separately below.
 */
const BASE_MODES = new Map<number, TransitMode>([
  [0, 'tram'],
  [1, 'subway'],
  [2, 'rail'],
  [3, 'bus'],
  [4, 'ferry'],
  [5, 'tram'],
  [6, 'aerial-lift'],
  [7, 'funicular'],
  [11, 'bus'],
  [12, 'monorail'],
]);

/**
 * GTFS extended route types grouped into the closest base mode. The ranges are documented per
 * hundred at gtfs.org; anything outside them gets the neutral glyph.
 */
const EXTENDED_MODES: ReadonlyArray<readonly [number, number, TransitMode]> = [
  [100, 199, 'rail'],
  [200, 299, 'bus'],
  [300, 399, 'rail'],
  [400, 499, 'subway'],
  [500, 599, 'subway'],
  [600, 699, 'subway'],
  [700, 799, 'bus'],
  [800, 899, 'bus'],
  [900, 999, 'tram'],
  [1000, 1099, 'ferry'],
  [1200, 1299, 'ferry'],
  [1300, 1399, 'aerial-lift'],
  [1400, 1499, 'funicular'],
];

/**
 * Glyphs from Material Symbols (rounded, weight 400), vendored under `icons/` with their
 * Apache-2.0 licence. MapLibre renders images, not icon-font glyphs, so the SVG source is
 * rasterised into the style instead of importing a Vaadin icon component.
 */
const GLYPHS: Record<TransitMode, string> = {
  bus: busIcon,
  tram: tramIcon,
  rail: railIcon,
  subway: subwayIcon,
  ferry: ferryIcon,
  monorail: monorailIcon,
  'aerial-lift': aerialLiftIcon,
  funicular: funicularIcon,
  generic: genericIcon,
};

/**
 * Pixel size of a rasterised glyph. At a pixel ratio of two the image is drawn at 24 CSS pixels,
 * which the map then scales down to the marker size.
 */
const ICON_SIZE = 48;
const ICON_PIXEL_RATIO = 2;

/** Image name for a mode, shared by the style expression and the image registration. */
export function modeIconName(mode: TransitMode): string {
  return `transit-mode-${mode}`;
}

/**
 * Groups a GTFS route type into the mode whose glyph represents it.
 *
 * @param routeType GTFS route_type, or null when the feed publishes none
 */
export function modeForRouteType(routeType: number | null): TransitMode {
  if (routeType === null) {
    return 'generic';
  }
  const base = BASE_MODES.get(routeType);
  if (base) {
    return base;
  }
  const range = EXTENDED_MODES.find(([min, max]) => routeType >= min && routeType <= max);
  return range ? range[2] : 'generic';
}

/**
 * Registers the mode glyphs on the map. Safe to call after every style load: images belong to the
 * style, so a theme switch drops them and the next call restores them.
 */
export function installModeIcons(map: MapLibreMap): void {
  for (const mode of Object.keys(GLYPHS) as TransitMode[]) {
    const name = modeIconName(mode);
    if (!map.hasImage(name)) {
      rasteriseIntoMap(map, name, GLYPHS[mode]);
    }
  }
}

/**
 * Browsers decode SVG images asynchronously, so the glyph is drawn onto a canvas once it has
 * loaded and only then added to the map. Adding an image repaints the map, which is what makes the
 * icons appear on a layer whose source was already set.
 */
function rasteriseIntoMap(map: MapLibreMap, name: string, svg: string): void {
  const image = new Image(ICON_SIZE, ICON_SIZE);
  image.onload = () => {
    const data = drawToImageData(image);
    if (!data || map.hasImage(name)) {
      return;
    }
    map.addImage(name, data, { pixelRatio: ICON_PIXEL_RATIO });
    map.triggerRepaint();
  };
  image.onerror = () => console.warn(`[live-map] could not draw the ${name} icon`);
  image.src = svgDataUrl(svg);
}

function drawToImageData(image: HTMLImageElement): ImageData | undefined {
  const canvas = document.createElement('canvas');
  canvas.width = ICON_SIZE;
  canvas.height = ICON_SIZE;
  const context = canvas.getContext('2d');
  if (!context) {
    return undefined;
  }
  context.drawImage(image, 0, 0, ICON_SIZE, ICON_SIZE);
  return context.getImageData(0, 0, ICON_SIZE, ICON_SIZE);
}

function svgDataUrl(markup: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(whiteGlyph(markup))}`;
}

/** The glyphs ship black; the marker's coloured circle supplies the background, so it is white. */
function whiteGlyph(markup: string): string {
  return markup.replace('<svg ', '<svg fill="#ffffff" ');
}
