package xyz.mcxross.flare.hyperliquid.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment
import xyz.mcxross.flare.hyperliquid.model.CancelOrders
import xyz.mcxross.flare.hyperliquid.model.CancelRequest
import xyz.mcxross.flare.hyperliquid.model.CancelTwap
import xyz.mcxross.flare.hyperliquid.model.L1Action
import xyz.mcxross.flare.hyperliquid.model.PlaceTwap
import xyz.mcxross.flare.hyperliquid.model.UpdateLeverage
import xyz.mcxross.flare.hyperliquid.model.UsdClassTransfer
import xyz.mcxross.flare.hyperliquid.model.UsdSend
import xyz.mcxross.flare.hyperliquid.model.UserSignedAction
import xyz.mcxross.flare.hyperliquid.model.VaultTransfer
import xyz.mcxross.flare.hyperliquid.model.Withdraw
import xyz.mcxross.flare.hyperliquid.model.toHlDecimal

/**
 * The fund moves and TWAPs Flare sends, and actions it signs for a sub-account, against what
 * hyperliquid-python-sdk 0.24.0 builds and signs for them (exchange.py's `withdraw_from_bridge`,
 * `usd_transfer`, `usd_class_transfer` and `vault_usd_transfer` dicts; `action_hash`, `l1_payload`,
 * `user_signed_payload` and eth_account's `encode_typed_data`). The SDK has no TWAP methods, so
 * those dicts follow the exchange endpoint docs and are hashed by the SDK's own `action_hash`.
 *
 * L1 actions are hashed from their msgpack bytes, so their JSON and msgpack must match byte for
 * byte. A user-signed action's JSON is not what is signed and the SDK appends its chain fields
 * last, so it must match as an object; its EIP-712 signing input must match exactly.
 */
class SdkFundsVectorsTest {
  @Test
  fun withdrawalsAndTransfersMatchTheSdk() {
    user(
      Withdraw(DESTINATION, "5.5".toHlDecimal()),
      """{"destination":"0x5e9ee1089755c3435139848e47e6635505d5a13a","amount":"5.5","time":1700000000000,"type":"withdraw3","signatureChainId":"0x66eee","hyperliquidChain":"Testnet"}""",
      "0x1901feb1393ca4412a4ca577bd51d04f0a77033514c602f4d0a11490fb95f7428df6459dbbfca65612f0f379dfe45c426fddd2009046416131cbe5db6dbe223ea4c8",
    )
    user(
      UsdSend(DESTINATION, "12.345678".toHlDecimal()),
      """{"destination":"0x5e9ee1089755c3435139848e47e6635505d5a13a","amount":"12.345678","time":1700000000000,"type":"usdSend","signatureChainId":"0x66eee","hyperliquidChain":"Testnet"}""",
      "0x1901feb1393ca4412a4ca577bd51d04f0a77033514c602f4d0a11490fb95f7428df6f612ba52554538eae8450a05643d2636147333f31fadde2cee4372cb9ac050a4",
    )
  }

  @Test
  fun perpSpotMovesMatchTheSdkForTheAccountAndASubAccount() {
    user(
      UsdClassTransfer("3".toHlDecimal(), toPerp = false),
      """{"type":"usdClassTransfer","amount":"3","toPerp":false,"nonce":1700000000000,"signatureChainId":"0x66eee","hyperliquidChain":"Testnet"}""",
      "0x1901feb1393ca4412a4ca577bd51d04f0a77033514c602f4d0a11490fb95f7428df6fc4d87b47e9f9c43f9fa835dd36ee397137e16a584caee73dd351855c57c9306",
    )
    user(
      UsdClassTransfer("2.5".toHlDecimal(), toPerp = true, subAccount = VAULT),
      """{"type":"usdClassTransfer","amount":"2.5 subaccount:0x1719884eb866cb12b2287399b15f7db5e7d775ea","toPerp":true,"nonce":1700000000000,"signatureChainId":"0x66eee","hyperliquidChain":"Testnet"}""",
      "0x1901feb1393ca4412a4ca577bd51d04f0a77033514c602f4d0a11490fb95f7428df636d3a0d46b67a94d35f5686203f0f9b8f2eaef8bea266e7b1f50d41e48fb499f",
    )
  }

  @Test
  fun anAgentSignedVaultRedemptionMatchesTheSdk() =
    l1(
      VaultTransfer.ofUsdc(HLP_VAULT, isDeposit = false, "2.5".toHlDecimal()),
      vault = null,
      json = """{"type":"vaultTransfer","vaultAddress":"0xa15099a30bbf2e68942d6f4c43d70d04faeab0a0","isDeposit":false,"usd":2500000}""",
      msgpack = "84a474797065ad7661756c745472616e73666572ac7661756c7441646472657373d92a307861313530393961333062626632653638393432643666346334336437306430346661656162306130a969734465706f736974c2a3757364ce002625a0",
      hash = "0x5343c71641e7d83814dc4de5aaad45902779a7bea0ace561e92a2be3f8e9fcdc",
      mainnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa404078594f54f2d15b50b23f58bfbe22c62bba5600f2938cf14342a84c698be9e789",
      testnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa4040a24a249faf3e118238c158b9a87f6a018b294ec260bf4eaba8774977b48f4cc5",
    )

  @Test
  fun aTwapMatchesTheSdkForTheAccountAndASubAccount() {
    l1(
      TWAP,
      vault = null,
      json = TWAP_JSON,
      msgpack = TWAP_MSGPACK,
      hash = "0xe31610b5e76ed9c72da4d773868099f10f7cdd1a8e4d5aa7027ee3194d5a21d0",
      mainnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa4040d5f61a7a8d9a35e280b3b1dc17f581dbb812bed360d35ed663e643ad33da5f2f",
      testnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa4040485eb9d3154abdaa95e088366ae7beca935b0ed11fcb46a172ff2f57f4f3aacc",
    )
    l1(
      TWAP,
      vault = VAULT,
      json = TWAP_JSON,
      msgpack = TWAP_MSGPACK,
      hash = "0xde55be6998cafd2698a72d2165d486bc034249aadcd99b02c186ff8ac60cfbf3",
      mainnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa40409c6dd58c341782c2228fe31022cc6c346c221b049ef1f7e141eebd422dc96bea",
      testnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa40401bed4f243e93458c4d7a9651ede5f6f339bb0d9d792f82eed2434ddc49d60d1b",
    )
  }

  @Test
  fun subAccountActionsSignWithItsVaultAddress() {
    l1(
      CancelTwap(0, 77_738_308L),
      vault = VAULT,
      json = """{"type":"twapCancel","a":0,"t":77738308}""",
      msgpack = "83a474797065aa7477617043616e63656ca16100a174ce04a23144",
      hash = "0x201babb7586390bbf6745e71caecac72ed2521b5441bcc213bdd265fa11d26d3",
      mainnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa40405d85156ec03d0b292c61faaac156e78ded79a55a271773f8c7bf6dc7e6cb959d",
      testnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa40406ab18ed596f37dfa885a473898dfd64bdc1ca27bbd5ab759bab98b9ba0d1ad88",
    )
    l1(
      CancelOrders(listOf(CancelRequest(0, 123_456L))),
      vault = VAULT,
      json = """{"type":"cancel","cancels":[{"a":0,"o":123456}]}""",
      msgpack = "82a474797065a663616e63656ca763616e63656c739182a16100a16fce0001e240",
      hash = "0xc17c79736246605486c0c8007ad835f58a397fd26c32871bb3b67d6bbf111704",
      mainnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa40401febf858eea82ec355abb1c1c21a8727c40dae7af1724510b131be08fb948f70",
      testnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa4040c4579ee4019c06594f82281260807aebdeaea51192032ad54e5d92edd50e5ebc",
    )
    l1(
      UpdateLeverage(0, isCross = false, leverage = 5),
      vault = VAULT,
      json = """{"type":"updateLeverage","asset":0,"isCross":false,"leverage":5}""",
      msgpack = "84a474797065ae7570646174654c65766572616765a5617373657400a7697343726f7373c2a86c6576657261676505",
      hash = "0xb1eda7517d767c0bbfa6ec26e670660f66c4ef448260e733351996b9ac013ab9",
      mainnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa4040c2fdd8e61c9ea9bdd0708ca6ff7ce52acafe38c6cce5ea23e9afebcf85fe3a24",
      testnet = "0x1901d79297fcdf2ffcd4ae223d01edaa2ba214ff8f401d7c9300d995d17c82aa4040ba20933b9c39902e20f40e804d280b7a1f936b7afb5f81c545e29c4305e5de03",
    )
  }

  private fun user(action: UserSignedAction, sdkJson: String, testnet: String) {
    val deployment = HyperliquidDeployment.Testnet
    assertEquals(Json.parseToJsonElement(sdkJson), action.toWire(deployment, NONCE), action.type)
    val input = UserSigning.signingInput(action.message(deployment, NONCE), deployment.signatureChainId)
    assertEquals(testnet, input.toHex0x(), action.type)
  }

  private fun l1(
    action: L1Action,
    vault: String?,
    json: String,
    msgpack: String,
    hash: String,
    mainnet: String,
    testnet: String,
  ) {
    val wire = action.toWire()
    assertEquals(json, wire.toString())
    assertEquals(msgpack, MsgPack.encode(wire).toHexDigits())
    assertEquals(hash, L1Signing.actionHash(wire, NONCE, vault, EXPIRES_AFTER).toHex0x())
    for ((deployment, expected) in
      listOf(HyperliquidDeployment.Mainnet to mainnet, HyperliquidDeployment.Testnet to testnet)) {
      assertEquals(
        expected,
        L1Signing.signingInput(wire, NONCE, deployment, vault, EXPIRES_AFTER).toHex0x(),
        "${action.type} ${deployment.network}",
      )
    }
  }

  private companion object {
    const val NONCE = 1_700_000_000_000L
    const val EXPIRES_AFTER = NONCE + 60_000L
    const val VAULT = VectorCases.VAULT
    const val DESTINATION = VectorCases.DESTINATION
    const val HLP_VAULT = VectorCases.HLP_VAULT
    val TWAP = PlaceTwap(0, isBuy = true, "0.01".toHlDecimal(), reduceOnly = false, minutes = 60, randomize = false)
    const val TWAP_JSON = """{"type":"twapOrder","twap":{"a":0,"b":true,"s":"0.01","r":false,"m":60,"t":false}}"""
    const val TWAP_MSGPACK = "82a474797065a9747761704f72646572a47477617086a16100a162c3a173a4302e3031a172c2a16d3ca174c2"
  }
}
