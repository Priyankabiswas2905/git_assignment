# Change Log
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## 0.4.0 - 2018-05-15

### Changed
 - Increased default maximum request size to 50 MB.
   [BD-2041](https://opensource.ncsa.illinois.edu/jira/browse/BD-2041)

### Added
 - Added a GET endpoint to retrieve the log details of a conversion.
   [BD-2067](https://opensource.ncsa.illinois.edu/jira/browse/BD-2067)

## 0.3.1 - 2018-01-25

### Fixed
- Fixed bug in swagger.json - API documentation not getting displayed because of an extra comma in swagger.json.

## 0.3.0 - 2018-01-12

### Fixed
- Fixed bug with POST endpoint /conversions/software/{software}/{output_format}. 
  [BD-1747](https://opensource.ncsa.illinois.edu/jira/browse/BD-1747)
- Added missing GET endpoint /conversions/software/{software}/{output_format}/{file_url}. 
  [BD-1754](https://opensource.ncsa.illinois.edu/jira/browse/BD-1754)
- Block when writing events to Mongo. This should fix bd tests.
  [BD-1865](https://opensource.ncsa.illinois.edu/jira/browse/BD-1865)
- Fixed Polyglot output file URL to point to new API endpoint.
  [BD-1746](https://opensource.ncsa.illinois.edu/jira/browse/BD-1746)

## 0.2.1 - 2017-07-09
### Fixed
- Added several /conversions/* endpoints that were missing.
- Fixed file upload issues in swagger definitions.
- Added events/stats definitions to swagger docs.

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
