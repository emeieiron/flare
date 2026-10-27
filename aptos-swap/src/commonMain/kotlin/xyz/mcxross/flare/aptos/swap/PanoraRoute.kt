package xyz.mcxross.flare.aptos.swap

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.TypeTag
import xyz.mcxross.kaptos.move.MoveArgument

/** Schema pinned to Panora's deployed router_entry ABI. Unknown shapes are rejected. */
internal object PanoraRoute {
  val typeArguments = List(32) { TypeTag.fromString("0x1::string::String") }
  private val types = listOf(
    "option:signer", "address", "u64", "u8", "v:u8", "v:v:v:u8", "v:v:v:u64",
    "v:v:v:bool", "v:v:u8", "v:v:v:address", "v:v:address", "v:v:address",
    "option:v:v:v:v:v:u8", "v:v:v:u64", "option:v:v:v:u8", "address", "v:u64",
    "u64", "u64", "address",
  )

  fun validate(tx: JsonObject, request: AptosSwapRequest, maxInput: ULong, minOutput: ULong): List<MoveArgument> {
    require(tx.string("function") == AptosSwapAssets.ROUTER) { "Unknown swap router" }
    val tags = tx.getValue("type_arguments").jsonArray
    require(tags.size == 32 && tags.all {
      TypeTag.fromString(it.jsonPrimitive.content) == TypeTag.fromString("0x1::string::String")
    }) { "Only fungible-asset routes are supported" }
    val args = tx.getValue("arguments").jsonArray
    require(args.size == types.size) { "Unsupported router argument count" }
    require(args[0] == JsonNull) { "Secondary swap signers are not supported" }
    require(sameAddress(args[1].jsonPrimitive.content, request.owner)) { "Wrong swap recipient" }
    require(args[2].jsonPrimitive.content == if (request.kind == SwapKind.EXACT_INPUT) "1" else "2")
    require(args[3].jsonPrimitive.content == "1") { "Unsupported router version" }
    require(args[8] == JsonArray(listOf(JsonArray(listOf(JsonPrimitive(1)))))) {
      "Only one primary fungible-asset withdrawal is supported"
    }
    val sources = args[10].jsonArray
    require(sources.isNotEmpty() && sources.first().jsonArray.size == 1 &&
      sameAddress(sources.first().jsonArray.single().jsonPrimitive.content, AptosSwapAssets.APT))
    for (index in listOf(10, 11)) for (row in args[index].jsonArray) for (asset in row.jsonArray) {
      val address = asset.jsonPrimitive.content
      require(sameAddress(address, AptosSwapAssets.APT) || sameAddress(address, AptosSwapAssets.USDC)) {
        "Routes through other assets are not supported"
      }
    }
    require(sameAddress(args[15].jsonPrimitive.content, AptosSwapAssets.USDC)) { "Wrong output asset" }
    val inputs = args[16].jsonArray
    require(inputs.size == 1 && inputs.single().jsonPrimitive.content.toULong() == maxInput) {
      "Router input does not match the reviewed limit"
    }
    require(args[17].jsonPrimitive.content.toULong() == minOutput) { "Wrong output protection" }
    require(args[18].jsonPrimitive.content.toULong() == 10_000_000_000_000_000uL) {
      "Unsupported router fee multiplier"
    }
    var nodes = 0
    fun encode(type: String, value: JsonElement): MoveArgument {
      require(++nodes <= 8192) { "Swap route is too large" }
      if (type.startsWith("option:")) return MoveArgument.Option(
        if (value == JsonNull) null else encode(type.removePrefix("option:"), value)
      )
      if (type.startsWith("v:")) return MoveArgument.Vector(
        value.jsonArray.also { require(it.size <= 256) }.map { encode(type.removePrefix("v:"), it) }
      )
      val scalar = value.jsonPrimitive
      return when (type) {
        "u64" -> MoveArgument.U64(unsigned(scalar))
        "u8" -> MoveArgument.U8(unsigned(scalar).also { require(it <= 255uL) }.toUByte())
        "bool" -> MoveArgument.Bool(checkNotNull(scalar.booleanOrNull))
        "address" -> MoveArgument.Address(AccountAddress.fromString(scalar.content))
        else -> error("Unsupported router type")
      }
    }
    return types.zip(args).map { (type, value) -> encode(type, value) }
  }

  private fun unsigned(value: JsonPrimitive): ULong {
    require(value.content.matches(Regex("0|[1-9][0-9]*"))) { "Invalid router integer" }
    return value.content.toULong()
  }
}
