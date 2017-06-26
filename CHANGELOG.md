# Change Log
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## 0.2.0 - 2017-06-26
### Changed
- Store events in Mongodb, instead of Redis.
- Use docker volume to load application.conf. Also link to externalredis and mongodb containers.
- GET /tokens/:id doesn't require authentication anymore.
- Moved pytests to standalone repository.
- Major cleanup of Server.scala by moving most of the Service definitions to standalone objects.

### Added
- Track user quotas using total counts.
- Rate limiting using buckets.
- Endpoint to get latest n events.
- Missing endpoints using new prefixes (/conversions and /extractions).
- Store resource_id for each file or url submission and use that to moderate access to outputs.
- Store Polyglot and Clowder file id as a Uniform Resource Name (URN) in events in the case of a file manually uploaded.
- Added admin authorization filter. Admins are specified in the conf file.
- Authentication to Datawolf backend calls.
- swagger.yaml endpoint.

### Fixed
- HTTP OPTIONS was not supported for some endpoint. CORS would not work.
- Encode username for Crowd authentication provider.
- Forward client IP using X-Real-IP for logging events when behind proxy (nginx).
- Updated swagger definitions.

## 0.0.1 - 2016-12-01
### Added
- Basic functionality
