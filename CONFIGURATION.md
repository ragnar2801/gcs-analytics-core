# Configuration

This document outlines the key configuration properties for the GCS Analytics Core library.

## Configuration Properties

All configuration properties can be prefixed with a common string, e.g., `gcs.`. This prefix is not included in the tables below.

### General GCS Client Options

These properties govern the core connections, identity, and access parameters between your compute environment and Google Cloud Storage.

| Property | Description | Default Value |
| :--- | :--- | :--- |
| `client-lib-token` | Client library token. | - |
| `service.host` | The GCS service host. | - |
| `user-agent` | The user agent string. | - |
| `project-id` | The Google Cloud project ID for the GCS client. | - |
| `user-project` | Project ID whose Google Cloud Project's billing account should be charged for the operation being executed. | - |
| `decryption-key` | Decryption key for the object. | - |

### Caching and Prefetching

These settings control how aggressively the library prefetches and caches metadata (like Parquet footers) and small objects in memory. Proper configuration here significantly reduces latency and redundant network calls during metadata discovery phases.

| Property | Description | Default Value |
| :--- | :--- | :--- |
| `analytics-core.footer.prefetch.enabled` | Controls whether footer prefetching is enabled. | `true` |
| `analytics-core.small-file.footer.prefetch.size-bytes` | Footer prefetch size (in bytes) for files up to 1 GB. | `51200` (50 KB) |
| `analytics-core.large-file.footer.prefetch.size-bytes` | Footer prefetch size (in bytes) for files larger than 1 GB. | `1048576` (1 MB) |
| `analytics-core.footer.cache.enabled` | Controls whether the Parquet footer cache is enabled. | `false` |
| `analytics-core.footer.cache.max-size-bytes`                 | The maximum capacity (in bytes) to hold in the Parquet footer cache.                        | `104857600` (100 MB) |
| `analytics-core.small-file.cache.threshold-bytes` | Threshold (in bytes) below which small files are cached entirely. | `1048576` (1 MB) |
| `analytics-core.small-file.cache.enabled` | Controls whether the small object cache is enabled. | `false` |
| `analytics-core.small-file.cache.max-size-bytes` | The maximum capacity (in bytes) to hold in the small object cache. | `209715200` (200 MB) |
| `analytics-core.cache.disk.enabled` | Controls whether the worker-level (host-shared) disk cache is enabled. When enabled, footers and small objects are also cached on local disk (ideally SSD) in a directory shared by all executor processes on the host. | `false` |
| `analytics-core.cache.disk.directory` | Local directory backing the disk cache. Every executor process on the host must point at the same directory to share entries. Required when the disk cache is enabled. | - |
| `analytics-core.cache.disk.max-size-bytes` | The maximum total size (in bytes) of the disk cache. Enforced by admission control: writes are skipped once the limit is reached. | `10737418240` (10 GB) |
| `analytics-core.cache.disk.ttl-millis` | Time-to-live (in milliseconds) of a disk cache entry, measured from creation. `0` disables TTL-based expiry. Entries are keyed by object generation, so TTL is a disk-hygiene bound, not a correctness bound. | `86400000` (24 h) |
| `analytics-core.cache.disk.eviction.high-watermark` | Usage fraction of the maximum size above which background LRU eviction starts. | `0.95` |
| `analytics-core.cache.disk.eviction.low-watermark` | Usage fraction of the maximum size down to which background LRU eviction proceeds. | `0.85` |

### Read Performance and I/O Tuning

These parameters fine-tune the low-level data streaming behavior. They allow you to optimize thread concurrency, heuristic file access patterns, and vectored I/O merging to maximize data throughput against GCS.

| Property | Description | Default Value |
| :--- | :--- | :--- |
| `channel.read.chunk-size-bytes` | Chunk size for GCS channel reads. | - |
| `analytics-core.read.thread.count` | Number of threads for parallel read operations like vectored IO. | `16` |
| `analytics-core.read.vectored.range.merge-gap.max-bytes` | Maximum gap (in bytes) between ranges to merge in vectored reads. | `4096` (4 KB) |
| `analytics-core.read.vectored.range.merged-size.max-bytes` | Maximum size (in bytes) of a merged range in vectored reads. | `8388608` (8 MB) |
| `analytics-core.read.inplace-seek-limit-bytes` | In-place seek limit (in bytes). | `131072` (128 KB) |
| `analytics-core.read.file-access-pattern` | File access pattern. Supported values: `RANDOM`, `SEQUENTIAL`, `AUTO_SEQUENTIAL`, `AUTO_RANDOM`. | `AUTO_SEQUENTIAL` |
| `analytics-core.adaptive-read.sequential-read-threshold` | Threshold for number of sequential reads to switch to sequential mode. | `3` |
| `analytics-core.random-read.min-request-size` | Minimum request size for random reads. If the requested read size is smaller, it reads up to this size. | `131072` (128 KB) |


### Telemetry and Monitoring

These settings enable the emission of deep internal metrics—such as cache hit rates, operational durations, and throughput—to local logging consoles or distributed OpenTelemetry backends like Google Cloud Monitoring.

| Property | Description | Default Value |
| :--- | :--- | :--- |
| `analytics-core.telemetry.logging.enabled` | Controls whether logging telemetry reporter is enabled. | `false` |
| `analytics-core.telemetry.logging.level` | Specifies the log level for logging telemetry events. Supported: `TRACE`, `DEBUG`, `INFO`, `WARNING`, `ERROR`. | `DEBUG` |
| `analytics-core.telemetry.opentelemetry.enabled` | Controls whether OpenTelemetry integration is enabled. | `false` |
| `analytics-core.telemetry.opentelemetry.provider-type` | Specifies the OpenTelemetry provider type. Supported: `GLOBAL`, `LOGGING`, `CLOUD_MONITORING`. | `GLOBAL` |
| `analytics-core.telemetry.opentelemetry.export-interval-seconds` | The export interval in seconds for OpenTelemetry periodic metric readers. | `60` |
| `analytics-core.project-id` | Google Cloud project ID for exporting OpenTelemetry metrics (specifically for `CLOUD_MONITORING`). | - |

## Notes

* **Cloud Monitoring Flush Timeout**: When using `CLOUD_MONITORING` as the OpenTelemetry provider type, closing the last active GCS client instance triggers a telemetry flush. This operation is synchronous and can block the closing thread for up to 10 seconds to ensure all remaining metrics are successfully exported to Google Cloud Monitoring before the application shuts down.
