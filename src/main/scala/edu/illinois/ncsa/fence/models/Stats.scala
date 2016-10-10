package edu.illinois.ncsa.fence.models

import com.fasterxml.jackson.annotation.JsonProperty

/**
  * Statistics based on activity.
  */
case class Stats(bytes: BytesStats, conversionsNum: Int, extractionsNum: Int, keysNum: Int, tokensNum: Int)

case class BytesStats(conversions_bytes: Int, @JsonProperty("extraction_bytes") extractionBytes: Int)
