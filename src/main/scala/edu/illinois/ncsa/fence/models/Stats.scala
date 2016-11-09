package edu.illinois.ncsa.fence.models

import com.fasterxml.jackson.annotation.JsonProperty

/** Statistics based on activity */
case class Stats(bytes: BytesStats, conversionsNum: Long, extractionsNum: Long, keysNum: Long, tokensNum: Long)

case class BytesStats(conversions_bytes: Long, @JsonProperty("extraction_bytes") extractionBytes: Long)
