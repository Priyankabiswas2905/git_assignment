# Change Log
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## 0.2.0 - 2017-04-10
### Changed
- Store events in Mongodb, instead of Redis.

### Added
- Track user quotas using total counts.
- Rate limiting using buckets.
- Endpoint to get latest n events.

### Fixed
- HTTP OPTIONS was not supported for some endpoint. CORS would not work.
- Encode username for Crowd authentication provider.
- Forward client IP using X-Real-IP for logging events when behind proxy (nginx).

## 0.0.1 - 2016-12-01
### Added
- Basic functionality
