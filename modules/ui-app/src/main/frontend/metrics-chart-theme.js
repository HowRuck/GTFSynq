/*
 * Applies the Aura/Lumo theme to Chart.js charts.
 *
 * Chart.js draws on a canvas and cannot inherit CSS custom properties, so the
 * text, grid and font defaults are resolved from the theme tokens before a
 * chart is built and refreshed when the browser switches between light and
 * dark. Tokens are resolved through a hidden element because their values may
 * be light-dark() or color-mix() expressions, which a canvas cannot parse.
 */

const TEXT_TOKENS = [
  '--vaadin-text-color-secondary',
  '--lumo-secondary-text-color',
  '--lumo-body-text-color',
];

const BORDER_TOKENS = ['--vaadin-border-color-secondary', '--lumo-contrast-20pct'];

function resolveColor(names, fallback) {
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

function apply() {
  if (!window.Chart) {
    return;
  }
  window.Chart.defaults.color = resolveColor(TEXT_TOKENS, '#666666');
  window.Chart.defaults.borderColor = resolveColor(BORDER_TOKENS, 'rgba(0, 0, 0, 0.1)');
  window.Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
}

function watchColorScheme() {
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    apply();
    if (!window.Chart) {
      return;
    }
    document.querySelectorAll('canvas').forEach((canvas) => {
      const chart = window.Chart.getChart(canvas);
      if (chart) {
        chart.update('none');
      }
    });
  });
}

watchColorScheme();

window.gtfsynqChartTheme = { apply };
