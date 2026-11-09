/*
 * Applies the Aura/Lumo theme to Chart.js charts.
 *
 * Chart.js draws on a canvas and cannot inherit CSS custom properties, so the
 * text, grid, tick and font defaults are resolved from the theme tokens before a
 * chart is built and refreshed when the browser switches between light and
 * dark. Tokens are resolved through a hidden element because their values may
 * be light-dark() or color-mix() expressions, which a canvas cannot parse.
 *
 * Chart.js arrives as a global from the vaadin-chartjs-wrapper, not as an npm
 * import, so only the members used here are typed.
 */

interface ChartInstance {
  update(mode?: string): void;
}

interface AxisTicks {
  autoSkip: boolean;
  maxTicksLimit: number;
  maxRotation?: number;
  minRotation?: number;
  callback?: (value: string | number) => string;
  [key: string]: unknown;
}

interface AxisGrid {
  color: string;
  [key: string]: unknown;
}

interface AxisBorder {
  color: string;
  [key: string]: unknown;
}

interface ChartScale {
  ticks: AxisTicks;
  grid?: AxisGrid;
  border?: AxisBorder;
  [key: string]: unknown;
}

interface LegendLabels {
  usePointStyle: boolean;
  pointStyle: string;
  boxWidth: number;
  boxHeight: number;
  padding: number;
  color: string;
  font: { family: string; size: number };
}

interface ChartLegend {
  position: string;
  align: string;
  labels: LegendLabels;
}

interface TooltipContext {
  dataset: { label?: string };
  parsed: { y: number };
}

interface TooltipCallbacks {
  label: (context: TooltipContext) => string;
}

interface ChartTooltip {
  mode: string;
  intersect: boolean;
  backgroundColor: string;
  titleColor: string;
  bodyColor: string;
  borderColor: string;
  borderWidth: number;
  cornerRadius: number;
  padding: number;
  displayColors: boolean;
  boxWidth: number;
  boxHeight: number;
  callbacks: TooltipCallbacks;
}

interface ChartDefaults {
  color: string;
  borderColor: string;
  font: { family: string; size: number };
  layout: { padding: { top: number; right: number; bottom: number; left: number } };
  scales: { category: ChartScale; linear: ChartScale };
  plugins: { legend: ChartLegend; tooltip: ChartTooltip };
  elements: {
    line: { tension: number; borderWidth: number; pointRadius: number; pointHoverRadius: number };
  };
}

interface ChartConstructor {
  defaults: ChartDefaults;
  getChart(canvas: HTMLCanvasElement): ChartInstance | undefined;
}

interface ChartThemeWindow extends Window {
  Chart?: ChartConstructor;
  gtfsynqChartTheme?: { apply: () => void };
}

const chartWindow = window as ChartThemeWindow;

const TEXT_TOKENS = [
  '--vaadin-text-color-secondary',
  '--lumo-secondary-text-color',
  '--lumo-body-text-color',
] as const;

const BORDER_TOKENS = ['--vaadin-border-color-secondary', '--lumo-contrast-20pct'] as const;

function resolveColor(names: readonly string[], fallback: string): string {
  const root = getComputedStyle(document.documentElement);
  for (const name of names) {
    if (!root.getPropertyValue(name).trim()) {
      continue;
    }
    const probe = document.createElement('span');
    probe.style.display = 'none';
    probe.style.color = `var(${name})`;
    document.body.appendChild(probe);
    const resolved = getComputedStyle(probe).color;
    probe.remove();
    if (resolved) {
      return resolved;
    }
  }
  return fallback;
}

function apply(): void {
  const chart = chartWindow.Chart;
  if (!chart) {
    return;
  }

  const defaults = chart.defaults;
  const text = resolveColor(TEXT_TOKENS, '#666666');
  const background = resolveColor(['--vaadin-background-color'], '#ffffff');
  const border = resolveColor(BORDER_TOKENS, 'rgba(0, 0, 0, 0.1)');

  defaults.color = text;
  defaults.borderColor = border;
  defaults.font.family = getComputedStyle(document.body).fontFamily;
  defaults.font.size = 11;
  defaults.layout.padding = { top: 4, right: 8, bottom: 0, left: 4 };

  defaults.scales.category.ticks.autoSkip = true;
  defaults.scales.category.ticks.maxRotation = 0;
  defaults.scales.category.ticks.minRotation = 0;
  defaults.scales.category.ticks.maxTicksLimit = 6;
  defaults.scales.category.grid = { ...defaults.scales.category.grid, color: border };
  defaults.scales.category.border = { ...defaults.scales.category.border, color: border };

  defaults.scales.linear.ticks.autoSkip = true;
  defaults.scales.linear.ticks.maxTicksLimit = 5;
  defaults.scales.linear.ticks.callback = (value) => formatTick(Number(value));
  defaults.scales.linear.grid = { ...defaults.scales.linear.grid, color: border };
  defaults.scales.linear.border = { ...defaults.scales.linear.border, color: border };

  defaults.plugins.legend.position = 'top';
  defaults.plugins.legend.align = 'end';
  defaults.plugins.legend.labels.usePointStyle = true;
  defaults.plugins.legend.labels.pointStyle = 'circle';
  defaults.plugins.legend.labels.boxWidth = 6;
  defaults.plugins.legend.labels.boxHeight = 6;
  defaults.plugins.legend.labels.padding = 14;
  defaults.plugins.legend.labels.color = text;
  defaults.plugins.legend.labels.font = { ...defaults.font, size: 11 };

  // Inverting the text and page backgrounds gives a dark tooltip in light mode and a light one in
  // dark mode, which keeps it readable without a second set of theme tokens.
  defaults.plugins.tooltip.mode = 'index';
  defaults.plugins.tooltip.intersect = false;
  defaults.plugins.tooltip.backgroundColor = text;
  defaults.plugins.tooltip.titleColor = background;
  defaults.plugins.tooltip.bodyColor = background;
  defaults.plugins.tooltip.borderColor = border;
  defaults.plugins.tooltip.borderWidth = 1;
  defaults.plugins.tooltip.cornerRadius = 8;
  defaults.plugins.tooltip.padding = 10;
  defaults.plugins.tooltip.displayColors = true;
  defaults.plugins.tooltip.boxWidth = 8;
  defaults.plugins.tooltip.boxHeight = 8;
  defaults.plugins.tooltip.callbacks.label = (context) => {
    const label = context.dataset.label ? `${context.dataset.label}: ` : '';
    return `${label}${formatTick(context.parsed.y)}`;
  };

  defaults.elements.line.tension = 0.3;
  defaults.elements.line.borderWidth = 2;
  defaults.elements.line.pointRadius = 0;
  defaults.elements.line.pointHoverRadius = 4;
}

/**
 * Formats an axis tick compactly without flattening the small rate values: 380000000 becomes
 * 380M, while 0.06 and 4.55 keep the precision a rate chart needs.
 */
function formatTick(value: number): string {
  const magnitude = Math.abs(value);
  if (magnitude === 0) {
    return '0';
  }
  if (magnitude >= 1_000_000_000) {
    return `${trim((value / 1_000_000_000).toFixed(1))}B`;
  }
  if (magnitude >= 1_000_000) {
    return `${trim((value / 1_000_000).toFixed(1))}M`;
  }
  if (magnitude >= 1_000) {
    return `${trim((value / 1_000).toFixed(1))}k`;
  }
  if (magnitude >= 10) {
    return value.toFixed(0);
  }
  if (magnitude >= 0.01) {
    return trim(value.toFixed(2));
  }
  return trim(value.toPrecision(2));
}

function trim(value: string): string {
  return value.includes('.') ? value.replace(/0+$/, '').replace(/\.$/, '') : value;
}

function watchColorScheme(): void {
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    apply();
    const chart = chartWindow.Chart;
    if (!chart) {
      return;
    }
    document.querySelectorAll('canvas').forEach((canvas) => {
      chart.getChart(canvas)?.update('none');
    });
  });
}

watchColorScheme();

chartWindow.gtfsynqChartTheme = { apply };

export {};
