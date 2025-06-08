# HeliStates

Simple plugin for generating geographic regions. Configure in `config.yml`.

Key settings include:
- `sampleSpacing`  – step between sampled points in blocks.
- `slopeExtra`     – extra height difference added to the median when detecting barriers.
- `maxParallelSamples` – how many chunks are sampled concurrently (0 uses `CPU * 2`).
- `chunkTimeout`   – seconds to wait for each chunk to load.
