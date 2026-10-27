# toolarium-java-memory

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [ 1.0.2 ] - 2026-10-27

## [ 1.0.1 ] - 2026-10-27
### Changed
- Updated `toolarium-common` dependency from 1.2.0 to 1.2.1.
- Improved thread safety in `MemoryMonitor` and `MemoryInformationMonitorImpl`.
- `JvmMemoryReaderImpl` now skips unchanged readings for lower overhead.
- Final statistics report is emitted on stop.
- Replaced string concatenation with parameterized logging.

### Added
- `MemoryMonitorFactory.removeShutdownHook()` for classloader recycling environments.
- `ProcessUtil` gracefully handles Java 16+ strong encapsulation.
- Sorted-values cache in `SlidingWindow`.

### Fixed
- Fixed typos in `MemoryMonitor`.
- Defensive array clones in `MemoryMetricStatistics` and `MemoryInformation`.

## [ 1.0.0 ] - 2024-10-18
### Changed
- Setup initial version.
