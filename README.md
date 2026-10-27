[![License](https://img.shields.io/github/license/toolarium/toolarium-java-memory)](https://github.com/toolarium/toolarium-java-memory/blob/master/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.toolarium/toolarium-java-memory/1.0.0)](https://search.maven.org/artifact/com.github.toolarium/toolarium-java-memory/1.0.0/jar)
[![javadoc](https://javadoc.io/badge2/com.github.toolarium/toolarium-java-memory/javadoc.svg)](https://javadoc.io/doc/com.github.toolarium/toolarium-java-memory)

# toolarium-java-memory

Defines the toolarium java memory library.


## Built With

* [cb](https://github.com/toolarium/common-build) - The toolarium common build

## Versioning

We use [SemVer](http://semver.org/) for versioning. For the versions available, see the [tags on this repository](https://github.com/toolarium/toolarium-java-memory/tags).


### Gradle:
```groovy
dependencies {
    implementation "com.github.toolarium:toolarium-java-memory:1.0.0"
}
```

### Maven:
```xml
<dependency>
    <groupId>com.github.toolarium</groupId>
    <artifactId>toolarium-java-memory</artifactId>
    <version>1.0.0</version>
</dependency>
```


## Memory Monitoring

`IMemoryInformationMonitor` polls a memory source every 500 ms on a daemon thread and computes streaming
statistics (count, last, average, min, max, standard deviation and the percentiles Median/P75/P90/P95/P99/P999)
per `MemoryMetric`. All values are in bytes.

Two readers are available:

| Reader | Metrics | Use case |
|---|---|---|
| `MemoryInformationFileReaderImpl` | `TOTAL`, `USED`, `FREE`, `PROCESS`, `JVM_TOTAL`, `HEAP`, `NON_HEAP`, `HEAP_USED`*, `NON_HEAP_USED`*, `THREAD`, `GC`, `JIT` | Full OS + JVM view via `cb-meminfo.sh` file |
| `JvmMemoryReaderImpl` | `HEAP` (committed), `NON_HEAP` (committed), `HEAP_USED`, `NON_HEAP_USED` | JVM-only view directly from MXBean |

> \* `HEAP_USED` and `NON_HEAP_USED` are only present in the file when `cb-meminfo.sh` is invoked with `-jvm` and `jstat` is available on the path.

The statistics scope is controlled by the window size configured via `MemoryMonitorFactory`:

| Scope | Activation | Percentiles | Memory per metric |
|---|---|---|---|
| `WINDOW` (default) | `window(Duration)` non-null | exact, over the rolling window | window / 0.5 s × 8 bytes (9.6 KB for 10 min) |
| `LIFETIME` | `window(null)` | exact (HdrHistogram, error below 0.1%) | a few bytes + 8–24 KB for the histogram |

Multiple concurrent windows can be configured with `windows(Duration...)`. The file is read **once per poll cycle** and the same reading is fed into every window simultaneously.

### Quick start

```java
// File-based monitor (default), configured from environment variables / system properties
IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().getMemoryInformationMonitor();
monitor.start();   // safe to call before the file exists

// JVM-only monitor: no file needed
IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().newBuilder()
        .jvmReader()
        .build();
monitor.start();

// Multiple concurrent windows: file is read once, all windows updated from the same reading
IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().newBuilder()
        .windows(Duration.ofMinutes(5), Duration.ofHours(1))
        .build();
monitor.start();

// Primary window (first configured)
MemoryInformationStatistics stats5m = monitor.getLatestStatistics();

// Specific window by duration: throws IllegalArgumentException if the duration was not configured
MemoryInformationStatistics stats1h = monitor.getLatestStatistics(Duration.ofHours(1));

// Check if the data source is available (file present, no parse errors)
boolean available = monitor.isAvailable();

// Read a single percentile value (in bytes); null if no data yet
Long heapP95 = monitor.getPercentile(MemoryMetric.HEAP, Percentile.P95);

// Read all statistics of one metric
MemoryInformationStatistics stats = monitor.getLatestStatistics();
if (stats != null) {
    MemoryMetricStatistics process = stats.get(MemoryMetric.PROCESS);
    if (process != null) {
        long last = process.getLast();
        long max  = process.getMax();
        Long p95  = process.get(Percentile.P95);
    }
}

// Iterate over all metrics with data, e.g. for an export
MemoryInformationStatistics stats = monitor.getLatestStatistics();
if (stats != null) {
    for (MemoryMetric metric : stats.getMetrics()) {
        export(metric.getColumnName(), stats.get(metric));
    }
}
```

### Reporter

A reporter receives periodic statistics snapshots and problem notifications from the monitor.
Use the built-in log reporter or supply your own.

**Built-in log reporter**: writes a formatted summary to SLF4J at INFO level every 60 s:

```java
IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().newBuilder()
        .reporter(MemoryInformationReporterFactory.getInstance().newLogReporter())
        .reportInterval(Duration.ofSeconds(60))
        .build();
monitor.start();
```

**Custom reporter**: implement `IMemoryInformationReporter` directly, or extend
`AbstractMemoryInformationReporter` to inherit the formatted output and override only what you need:

```java
public class MyReporter extends AbstractMemoryInformationReporter {

    @Override
    public void report(MemoryInformationStatistics statistics) {
        // use formatStatistics(statistics) for a ready-made formatted string, or
        // access statistics.getMetrics() / statistics.get(metric) for raw values
        myMetricSystem.record("heap.p95", statistics.get(MemoryMetric.HEAP).get(Percentile.P95));
    }

    @Override
    public void reportProblem(String problem, String detail) {
        myAlertSystem.warn(problem + ": " + detail);
    }
}
```

Wire it in via the builder:

```java
IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().newBuilder()
        .reporter(new MyReporter())
        .reportInterval(Duration.ofSeconds(60))
        .build();
monitor.start();
```

The monitor calls:
- `start(intervalMs)`: once when the monitor starts
- `report(statistics)`: periodically at the configured interval; `statistics` is `null` if no data yet
- `reportProblem(problem, detail)`: once per distinct problem until it clears (e.g. file missing, format error)
- `end()`: once when the monitor stops

### Builder configuration

Use the builder to supply explicit values before the monitor is started:

```java
IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().newBuilder()
        .memoryInformationFile(Paths.get("/var/run/meminfo.out"))
        .window(Duration.ofMinutes(5))           // single window (shorthand for .windows(...))
        .staleThresholdMilliseconds(30_000)
        .reportIntervalMilliseconds(60_000)
        .build();
monitor.start();
```

Use `windows(Duration...)` to track multiple concurrent windows from a single file read:

```java
IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().newBuilder()
        .memoryInformationFile(Paths.get("/var/run/meminfo.out"))
        .windows(Duration.ofMinutes(5), Duration.ofHours(1))
        .build();
monitor.start();

// access each window independently; throws IllegalArgumentException if duration not configured
MemoryInformationStatistics stats5m = monitor.getLatestStatistics(Duration.ofMinutes(5));
MemoryInformationStatistics stats1h = monitor.getLatestStatistics(Duration.ofHours(1));
```

Any value not set in the builder falls back to the corresponding environment variable or system property:

| Variable | Default | Description |
|---|---|---|
| `TOOLARIUM_MEMORY_INFORMATION_WINDOW_MS` | `600000` | Sliding window length in milliseconds; `0` disables the window (lifetime scope) |
| `TOOLARIUM_MEMORY_INFORMATION_AUTO_LOG_INTERVAL_MS` | `0` | Auto-log interval in milliseconds; `0` disables it |
| `TOOLARIUM_MEMORY_INFORMATION_STALE_THRESHOLD_MS` | `15000` | Age after which statistics are considered stale in milliseconds; `0` disables detection |

The window size is fixed once the monitor is started. The stale threshold can be changed at any time via
the setter on `IMemoryInformationMonitor`.

### Memory information file format

The monitor reads a plain-text file of at most 1 KB. Two formats are supported:

**Header-based** (produced by [`cb-meminfo.sh`](https://github.com/toolarium/common-build) via `toolarium-java-runner.sh --observeMemoryCycle <seconds>`, default every 5 s):

```
Timestamp               Total          Used           Free           Process[4711]  JVM-Total      Heap           Non-Heap       Heap-Used      Non-Heap-Used  Thread         GC             JIT
2026-10-18 14:00:40     16847208448    9663676416     7183532032     524288000      268435456      134217728      52428800       104857600      47185920       2097152        5242880        4194304
```

A header line starting with `Timestamp` followed by a data line `<date> <time> <values...>`.
Columns are resolved by name; a suffix in brackets (e.g. `Process[4711]`) is ignored, the column order is
not relevant and unknown columns are ignored. `Total`, `Used` and `Free` are required.
Core JVM metrics (`Heap`, `Non-Heap`, `JIT`) are only tracked when all three are present in the header.
Extended JVM metrics (`JVM-Total`, `Heap-Used`, `Non-Heap-Used`, `Thread`, `GC`) are optional and tracked independently when present.
`Heap-Used` and `Non-Heap-Used` are written by `cb-meminfo.sh` only when `jstat` is available.

> **Note:** The column name `Code` is also accepted as an alias for `JIT` for backward compatibility with
> older versions of `cb-meminfo.sh`.

**Default**:

```
2024-01-15 10:00:00 8589934592 Total 4294967296 Used 4294967296 Free 524288000
```

A single data line `<date> <time> <total> <label> <used> <label> <free> [<label> <process>]`.

### Behaviour

* The in-file timestamp (`<date> <time>`) is used as change detection; a reading is recorded once per unique timestamp.
* An incomplete reading (field count does not match the header), a non-numeric value, or a value outside 0–64 TB is
  rejected and retried on the next poll. Writers should replace the file atomically (write to a temp file then `mv`).
* Only a regular file of at most 1 KB is read; symbolic links, pipes and devices are rejected. The file content is never logged.
* Problems are logged once and suppressed until recovery; a missing file is logged at info level.
* `isStale()` detects a stopped writer; a warning is logged once when the stale threshold is exceeded.
* The collector thread survives runtime exceptions; if it terminates unexpectedly the monitor is marked as stopped and can be restarted.

### Log output

`LogMemoryInformationReporterImpl` writes a formatted statistics snapshot to SLF4J at INFO level on each
report interval, and heap/non-heap details at DEBUG level. Each reading is also logged at DEBUG level on
`MemoryInformationMonitorImpl`.

#### File reader (`MemoryInformationFileReaderImpl`)

```
INFO  LogMemoryInformationReporterImpl - Start memory reporting, interval: 60000 ms.
```

Per reading (DEBUG on `MemoryInformationMonitorImpl`):
```
DEBUG MemoryInformationMonitorImpl -
=== Memory Reading [2026-10-18T14:00:40Z] ===
  Total        : 16847208448 bytes (15.7 GB)
  Used         : 9663676416 bytes (9.0 GB)
  Free         : 7183532032 bytes (6.7 GB)
  Process      : 524288000 bytes (500.0 MB)
  JVM-Total    : 268435456 bytes (256.0 MB)
  Heap         : 134217728 bytes (128.0 MB)
  Non-Heap     : 52428800 bytes (50.0 MB)
  Heap-Used    : 104857600 bytes (100.0 MB)
  Non-Heap-Used: 47185920 bytes (45.0 MB)
  Thread       : 2097152 bytes (2.0 MB)
  GC           : 5242880 bytes (5.0 MB)
  JIT          : 4194304 bytes (4.0 MB)
```

Each report interval (DEBUG on `LogMemoryInformationReporterImpl`, only when DEBUG enabled):
```
DEBUG LogMemoryInformationReporterImpl - Memory: heap [100.0 MB|128.0 MB|134.0 MB|140.0 MB], non heap [48.0 MB|50.0 MB|52.0 MB|55.0 MB] - [min|avg|last|max]
```

Each report interval (INFO):
```
INFO  LogMemoryInformationReporterImpl -
=== Memory Information Statistics (120 readings, last reading: 2026-10-18T14:00:40Z, age: 250 ms) ===
  Last          — Total: 15.7 GB, Used: 9.0 GB, Free: 6.7 GB, Process: 500.0 MB
--- Window (last 600 s) ---
  Total         — P95: 15.7 GB, Median: 15.6 GB, Spread: 100.0 MB, Min: 15.0 GB, Max: 16.0 GB
  Used          — P95: 9.2 GB, Median: 9.0 GB, Spread: 200.0 MB, Min: 8.5 GB, Max: 9.5 GB
  Free          — P95: 7.0 GB, Median: 6.7 GB, Spread: 300.0 MB, Min: 6.0 GB, Max: 7.5 GB
  Process       — P95: 500.0 MB, Median: 490.0 MB, Spread: 10.0 MB, Min: 480.0 MB, Max: 520.0 MB
  JVM-Total     — P95: 260.0 MB, Median: 256.0 MB, Spread: 4.0 MB, Min: 250.0 MB, Max: 270.0 MB
  Heap          — P95: 134.0 MB, Median: 128.0 MB, Spread: 6.0 MB, Min: 100.0 MB, Max: 140.0 MB
  Non-Heap      — P95: 52.0 MB, Median: 50.0 MB, Spread: 2.0 MB, Min: 48.0 MB, Max: 55.0 MB
  Heap-Used     — P95: 102.0 MB, Median: 100.0 MB, Spread: 2.0 MB, Min: 90.0 MB, Max: 110.0 MB
  Non-Heap-Used — P95: 46.0 MB, Median: 45.0 MB, Spread: 1.0 MB, Min: 43.0 MB, Max: 48.0 MB
  Thread        — P95: 2.0 MB, Median: 2.0 MB, Spread: 0.0 MB, Min: 1.5 MB, Max: 2.5 MB
  GC            — P95: 5.0 MB, Median: 5.0 MB, Spread: 0.0 MB, Min: 4.0 MB, Max: 6.0 MB
  JIT           — P95: 4.0 MB, Median: 4.0 MB, Spread: 0.0 MB, Min: 3.5 MB, Max: 4.5 MB
```

The `Last` line shows the most recent raw reading for OS metrics (Total, Used, Free, Process), the instant
snapshot before the statistics. `last reading` is the timestamp written into the file by `cb-meminfo.sh`.
`age` is how long ago Java captured the snapshot. `Spread` = P95 − Median; a large spread indicates the
metric fluctuates significantly above its typical level.

#### JVM reader (`JvmMemoryReaderImpl`)

Per reading (DEBUG on `MemoryInformationMonitorImpl`):
```
DEBUG MemoryInformationMonitorImpl -
=== Memory Reading [2026-10-19T10:00:00Z] ===
  Heap         : 134217728 bytes (128.0 MB)
  Non-Heap     : 52428800 bytes (50.0 MB)
  Heap-Used    : 104857600 bytes (100.0 MB)
  Non-Heap-Used: 47185920 bytes (45.0 MB)
```

Each report interval (DEBUG on `LogMemoryInformationReporterImpl`, only when DEBUG enabled):
```
DEBUG LogMemoryInformationReporterImpl - Memory: heap [100.0 MB|128.0 MB|134.0 MB|140.0 MB], non heap [48.0 MB|50.0 MB|52.0 MB|55.0 MB] - [min|avg|last|max]
```

Each report interval (INFO):
```
INFO  LogMemoryInformationReporterImpl -
=== Memory Information Statistics (120 readings, last reading: 2026-10-19T10:00:00Z, age: 12 ms) ===
  Last          — Heap: 128.0 MB, Non-Heap: 50.0 MB, Heap-Used: 100.0 MB, Non-Heap-Used: 45.0 MB
--- Window (last 600 s) ---
  Heap          — P95: 134.0 MB, Median: 128.0 MB, Spread: 6.0 MB, Min: 100.0 MB, Max: 140.0 MB
  Non-Heap      — P95: 52.0 MB, Median: 50.0 MB, Spread: 2.0 MB, Min: 48.0 MB, Max: 55.0 MB
  Heap-Used     — P95: 102.0 MB, Median: 100.0 MB, Spread: 2.0 MB, Min: 90.0 MB, Max: 110.0 MB
  Non-Heap-Used — P95: 46.0 MB, Median: 45.0 MB, Spread: 1.0 MB, Min: 43.0 MB, Max: 48.0 MB
```

Since there are no OS metrics, the `Last` line shows Heap and Non-Heap instead.

