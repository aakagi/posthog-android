package com.posthog.internal

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Keeps an explicit `null` apart from an omitted field. Registered as a [TypeAdapter] rather than
 * a deserializer because Gson hands a null token to a [TypeAdapter] instead of skipping it.
 */
internal class GsonConditionAggregationAdapter : TypeAdapter<ConditionAggregation>() {
    override fun read(reader: JsonReader): ConditionAggregation {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return ConditionAggregation(groupTypeIndex = null)
        }
        return ConditionAggregation(groupTypeIndex = reader.nextInt())
    }

    override fun write(
        writer: JsonWriter,
        value: ConditionAggregation?,
    ) {
        val groupTypeIndex = value?.groupTypeIndex
        if (groupTypeIndex != null) {
            writer.value(groupTypeIndex.toLong())
            return
        }
        // A JsonWriter that doesn't serialize nulls drops a null field along with its name. Pick
        // that per value, so an omitted field stays omitted and an explicit null is written.
        val serializeNulls = writer.serializeNulls
        writer.serializeNulls = value != null
        writer.nullValue()
        writer.serializeNulls = serializeNulls
    }
}
