package expo.modules.screenrecorder

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class RecordingOptions : Record {
  @Field
  val durationLimit: Double? = null

  @Field
  val quality: String? = "high"

  @Field
  val includeAudio: Boolean? = false
}