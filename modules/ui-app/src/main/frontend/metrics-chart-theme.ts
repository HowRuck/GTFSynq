/*
 * Applies the Aura/Lumo theme to Chart.js charts.
 *
 * Chart.js draws on a canvas and cannot inherit CSS custom properties, so the
 * text, grid and font defaults are resolved from the theme tokens before a
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

interface ChartDefaults {
  color: string;
  borderColor: string;
  font: {
    family: string;
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
  chart.defaults.color = resolveColor(TEXT_TOKENS, '#666666');
  chart.defaults.borderColor = resolveColor(BORDER_TOKENS, 'rgba(0, 0, 0, 0.1)');
  chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
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
