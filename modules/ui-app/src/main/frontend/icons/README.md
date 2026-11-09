# Transit mode icons

The `transit-*.svg` files are taken unchanged from **Material Symbols** (rounded, weight 400):

- Source: https://github.com/google/material-design-icons
- Package: `@material-symbols/svg-400`
- Copyright Google LLC
- Licensed under the Apache License, Version 2.0 (see `LICENSE` in this directory)

Only the glyphs used by the live map's mode markers are vendored, so the application does not
depend on the full Material Symbols package. The mapping from GTFS `route_type` to a file lives in
`../map-mode-icons.ts`.

| File | Material Symbols name |
|---|---|
| `transit-bus.svg` | `directions_bus` |
| `transit-tram.svg` | `tram` |
| `transit-rail.svg` | `train` |
| `transit-subway.svg` | `subway` |
| `transit-ferry.svg` | `directions_boat` |
| `transit-monorail.svg` | `monorail` |
| `transit-aerial-lift.svg` | `cable_car` |
| `transit-funicular.svg` | `funicular` |
| `transit-generic.svg` | `transit_ticket` |
