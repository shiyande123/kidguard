package com.kidguard.data.db

import android.util.Base64
import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Converters {
    /**
     * Serialize FloatArray to Base64-encoded byte array.
     * Preserves full float precision (no decimal truncation), ~3x smaller than CSV string.
     * Format: 4 bytes per float (native order), then Base64.
     */
    @TypeConverter
    fun fromFloatArray(value: FloatArray?): String? {
        if (value == null) return null
        val buf = ByteBuffer.allocate(value.size * 4)
            .order(ByteOrder.nativeOrder())
        for (f in value) buf.putFloat(f)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    /**
     * Deserialize Base64-encoded byte array back to FloatArray.
     */
    @TypeConverter
    fun toFloatArray(value: String?): FloatArray? {
        if (value.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            val floats = FloatArray(bytes.size / 4)
            buf.asFloatBuffer().get(floats)
            floats
        } catch (e: Exception) {
            null
        }
    }
}
