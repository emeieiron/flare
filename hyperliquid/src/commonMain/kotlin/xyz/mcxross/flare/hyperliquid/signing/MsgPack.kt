package xyz.mcxross.flare.hyperliquid.signing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The MessagePack subset Hyperliquid's L1 action hash needs, byte for byte what Python's
 * `msgpack.packb(action)` produces with default options:
 * - maps keep insertion order (fixmap, map16, map32);
 * - arrays (fixarray, array16, array32);
 * - strings as UTF-8 (fixstr, str8, str16, str32);
 * - `true`, `false`, `nil`;
 * - integers in their smallest form: non-negative ones as positive fixint or uint8/16/32/64,
 *   negative ones as negative fixint or int8/16/32/64.
 *
 * Actions are built as [JsonObject]s, which keep insertion order, so the same tree yields both the
 * request JSON and these bytes. A JSON number must be an integer; a fraction is rejected, since
 * Hyperliquid's actions carry every decimal as a string.
 */
object MsgPack {
  fun encode(value: JsonElement): ByteArray = Writer().apply { write(value) }.toByteArray()

  private class Writer {
    private var buffer = ByteArray(256)
    private var size = 0

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    fun write(value: JsonElement) {
      when (value) {
        is JsonNull -> byte(0xc0)
        is JsonObject -> {
          header(value.size, fix = 0x80, fixMax = 15, size16 = 0xde, size32 = 0xdf)
          for ((key, element) in value) {
            string(key)
            write(element)
          }
        }
        is JsonArray -> {
          header(value.size, fix = 0x90, fixMax = 15, size16 = 0xdc, size32 = 0xdd)
          value.forEach(::write)
        }
        is JsonPrimitive -> primitive(value)
      }
    }

    private fun primitive(value: JsonPrimitive) {
      if (value.isString) return string(value.content)
      value.booleanOrNull?.let {
        byte(if (it) 0xc3 else 0xc2)
        return
      }
      val text = value.content
      if (text.startsWith('-')) {
        signed(
          text.toLongOrNull() ?: throw IllegalArgumentException("MsgPack: unsupported number")
        )
      } else {
        unsigned(
          text.toULongOrNull() ?: throw IllegalArgumentException("MsgPack: unsupported number")
        )
      }
    }

    private fun unsigned(value: ULong) {
      when {
        value <= 0x7fuL -> byte(value.toInt())
        value <= 0xffuL -> {
          byte(0xcc)
          bigEndian(value, 1)
        }
        value <= 0xffffuL -> {
          byte(0xcd)
          bigEndian(value, 2)
        }
        value <= 0xffff_ffffuL -> {
          byte(0xce)
          bigEndian(value, 4)
        }
        else -> {
          byte(0xcf)
          bigEndian(value, 8)
        }
      }
    }

    private fun signed(value: Long) {
      if (value >= 0L) return unsigned(value.toULong())
      when {
        value >= -32L -> byte(value.toInt() and 0xff)
        value >= Byte.MIN_VALUE -> {
          byte(0xd0)
          bigEndian(value.toULong(), 1)
        }
        value >= Short.MIN_VALUE -> {
          byte(0xd1)
          bigEndian(value.toULong(), 2)
        }
        value >= Int.MIN_VALUE -> {
          byte(0xd2)
          bigEndian(value.toULong(), 4)
        }
        else -> {
          byte(0xd3)
          bigEndian(value.toULong(), 8)
        }
      }
    }

    private fun string(value: String) {
      val bytes = value.encodeToByteArray()
      val length = bytes.size
      when {
        length <= 31 -> byte(0xa0 or length)
        length <= 0xff -> {
          byte(0xd9)
          bigEndian(length.toULong(), 1)
        }
        length <= 0xffff -> {
          byte(0xda)
          bigEndian(length.toULong(), 2)
        }
        else -> {
          byte(0xdb)
          bigEndian(length.toULong(), 4)
        }
      }
      bytes(bytes)
    }

    private fun header(count: Int, fix: Int, fixMax: Int, size16: Int, size32: Int) {
      when {
        count <= fixMax -> byte(fix or count)
        count <= 0xffff -> {
          byte(size16)
          bigEndian(count.toULong(), 2)
        }
        else -> {
          byte(size32)
          bigEndian(count.toULong(), 4)
        }
      }
    }

    /** The low [width] bytes of [value], most significant first. */
    private fun bigEndian(value: ULong, width: Int) {
      for (index in width - 1 downTo 0) byte(((value shr (8 * index)) and 0xffuL).toInt())
    }

    private fun byte(value: Int) {
      ensure(1)
      buffer[size++] = value.toByte()
    }

    private fun bytes(value: ByteArray) {
      ensure(value.size)
      value.copyInto(buffer, size)
      size += value.size
    }

    private fun ensure(extra: Int) {
      if (size + extra > buffer.size) {
        buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
      }
    }
  }
}
