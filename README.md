# HeliStates

Simple plugin for generating geographic regions. Configure in `config.yml`.

Key settings include:
- `steepSlope` – height difference that forms a barrier between regions.
- `similarBiomes` – groups of biome names treated as the same when building borders.
- `maxParallelSamples` – how many chunks are sampled concurrently (0 uses `CPU * 2`).
