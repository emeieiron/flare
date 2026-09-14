package xyz.mcxross.flare.decibel.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.flare.decibel.model.MarginMode
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.TimeInForce
import xyz.mcxross.flare.decibel.model.ValidatedOrder
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosError
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.MoveFunction
import xyz.mcxross.kaptos.model.MoveModule
import xyz.mcxross.kaptos.model.MoveModuleBytecode
import xyz.mcxross.kaptos.model.MoveVisibility
import xyz.mcxross.kaptos.model.TransactionPayload
import xyz.mcxross.kaptos.move.MoveArgument

class DecibelTradingPayloadTest {
  @Test
  fun commandCatalogUsesDocumentedEntryFunctions() = runTest {
    withService { service ->
      val order = order()
      val commands =
        listOf(
          DecibelCommand.CreateSubaccount to ("dex_accounts_entry" to "create_new_subaccount"),
          DecibelCommand.Deposit(SUBACCOUNT, ASSET, 1uL) to ("dex_accounts_entry" to "deposit_to_subaccount_at"),
          DecibelCommand.Withdraw(SUBACCOUNT, ASSET, 1uL) to ("dex_accounts_entry" to "withdraw_from_cross_collateral"),
          DecibelCommand.DelegateTrading(SUBACCOUNT, DELEGATE, 100uL) to
            ("dex_accounts_entry" to "delegate_all_trading_to_for_subaccount"),
          DecibelCommand.RevokeDelegation(SUBACCOUNT, DELEGATE) to ("dex_accounts_entry" to "revoke_delegation"),
          DecibelCommand.ConfigureMarket(
            SUBACCOUNT,
            MARKET,
            MarginMode.CROSS,
            10u,
          ) to ("dex_accounts_entry" to "configure_user_settings_for_market"),
          DecibelCommand.PlaceOrder(SUBACCOUNT, order) to ("dex_accounts_entry" to "place_order_to_subaccount"),
          DecibelCommand.PlaceSpotOrder(SUBACCOUNT, order) to ("dex_accounts_spot_entry" to "place_spot_order_to_subaccount"),
          DecibelCommand.CancelOrder(SUBACCOUNT, MARKET, MAX_U128) to ("dex_accounts_entry" to "cancel_order_to_subaccount"),
          DecibelCommand.CancelSpotOrder(SUBACCOUNT, MARKET, MAX_U128) to ("dex_accounts_spot_entry" to "cancel_spot_order_to_subaccount"),
          DecibelCommand.CancelPositionTpSl(SUBACCOUNT, MARKET, MAX_U128) to
            ("dex_accounts_entry" to "cancel_tp_sl_order_for_position"),
          DecibelCommand.SetPositionTpSl(
            subaccount = SUBACCOUNT,
            market = MARKET,
            takeProfitTrigger = 21uL,
          ) to ("dex_accounts_entry" to "place_tp_sl_order_for_position"),
        )

      commands.forEach { (command, target) ->
        val (module, function) = target
        val payload = service.payload(command).success()
        assertEquals(DEPLOYMENT.packageAddress, payload.call.module.address.toStringLong())
        assertEquals(module, payload.call.module.name.toString())
        assertEquals(function, payload.call.function.toString())
        assertEquals(emptyList(), payload.call.typeArguments)
      }
    }
  }

  @Test
  fun placeSpotOrderEncodingPinsAllDocumentedArgumentsAndNoBuilderFee() = runTest {
    withService { service ->
      val payload = service.payload(DecibelCommand.PlaceSpotOrder(SUBACCOUNT, order())).success()

      assertEquals(
        listOf(
          addressHex(SUBACCOUNT),
          addressHex(MARKET),
          "0807060504030201",
          "0900000000000000",
          "01",
          "02",
          "00",
          "00",
        ),
        payload.argumentHex(),
      )
    }
  }

  @Test
  fun cancelSpotOrderEncodingMatchesAbi() = runTest {
    withService { service ->
      val payload =
        service.payload(DecibelCommand.CancelSpotOrder(SUBACCOUNT, MARKET, MAX_U128)).success()

      assertEquals(
        listOf(
          addressHex(SUBACCOUNT),
          addressHex(MARKET),
          "ffffffffffffffffffffffffffffffff",
        ),
        payload.argumentHex(),
      )
    }
  }

  @Test
  fun placeOrderEncodingPinsAllDocumentedArgumentsAndNoBuilderFee() = runTest {
    withService { service ->
      val payload = service.payload(DecibelCommand.PlaceOrder(SUBACCOUNT, order())).success()

      assertEquals(
        listOf(
          addressHex(SUBACCOUNT),
          addressHex(MARKET),
          "0807060504030201",
          "0900000000000000",
          "01",
          "02",
          "00",
          "01076f726465722d37",
          "00",
          "010a00000000000000",
          "010b00000000000000",
          "010c00000000000000",
          "010d00000000000000",
          "00",
          "00",
        ),
        payload.argumentHex(),
      )
    }
  }

  @Test
  fun positionTpSlKeepsSizeAndStopLossSlotsInAbiOrder() = runTest {
    withService { service ->
      val payload =
        service
          .payload(
            DecibelCommand.SetPositionTpSl(
              subaccount = SUBACCOUNT,
              market = MARKET,
              takeProfitTrigger = 21uL,
              takeProfitLimit = 22uL,
              takeProfitSize = 23uL,
              stopLossTrigger = 31uL,
              stopLossLimit = 32uL,
              stopLossSize = 33uL,
            )
          )
          .success()

      assertEquals(
        listOf(
          addressHex(SUBACCOUNT),
          addressHex(MARKET),
          "011500000000000000",
          "011600000000000000",
          "011700000000000000",
          "011f00000000000000",
          "012000000000000000",
          "012100000000000000",
          "00",
          "00",
        ),
        payload.argumentHex(),
      )
    }
  }

  @Test
  fun fundingDelegationConfigurationAndCancellationHaveStableBcs() = runTest {
    withService { service ->
      assertEquals(
        listOf(addressHex(SUBACCOUNT), addressHex(ASSET), "40420f0000000000"),
        service
          .payload(DecibelCommand.Deposit(SUBACCOUNT, ASSET, 1_000_000uL))
          .success()
          .argumentHex(),
      )
      assertEquals(
        listOf(addressHex(SUBACCOUNT), addressHex(DELEGATE), "010807060504030201"),
        service
          .payload(
            DecibelCommand.DelegateTrading(
              SUBACCOUNT,
              DELEGATE,
              0x0102030405060708uL,
            )
          )
          .success()
          .argumentHex(),
      )
      assertEquals(
        listOf(addressHex(SUBACCOUNT), addressHex(MARKET), "00", "64"),
        service
          .payload(
            DecibelCommand.ConfigureMarket(
              SUBACCOUNT,
              MARKET,
              MarginMode.ISOLATED,
              100u,
            )
          )
          .success()
          .argumentHex(),
      )
      assertEquals(
        listOf(addressHex(SUBACCOUNT), "ff".repeat(16), addressHex(MARKET)),
        service
          .payload(DecibelCommand.CancelOrder(SUBACCOUNT, MARKET, MAX_U128))
          .success()
          .argumentHex(),
      )
      assertEquals(
        listOf(addressHex(SUBACCOUNT), addressHex(MARKET), "ff".repeat(16)),
        service
          .payload(DecibelCommand.CancelPositionTpSl(SUBACCOUNT, MARKET, MAX_U128))
          .success()
          .argumentHex(),
      )
    }
  }

  @Test
  fun invalidCommandValuesReturnTypedValidationFailure() = runTest {
    withService { service ->
      val invalidAddress =
        assertIs<AptosResult.Failure>(
          service.payload(DecibelCommand.Deposit("not-an-address", ASSET, 1uL))
        )
      assertIs<AptosError.Validation>(invalidAddress.error)

      val invalidLeverage =
        assertIs<AptosResult.Failure>(
          service.payload(
            DecibelCommand.ConfigureMarket(
              SUBACCOUNT,
              MARKET,
              MarginMode.CROSS,
              101u,
            )
          )
        )
      assertIs<AptosError.Validation>(invalidLeverage.error)
    }
  }

  private suspend fun withService(block: suspend (DecibelTradingService) -> Unit) {
    val aptos = Aptos()
    try {
      aptos.transactions.preloadModuleAbis(DECIBEL_ENTRY_ABI).success()
      aptos.transactions.preloadModuleAbis(DECIBEL_SPOT_ENTRY_ABI).success()
      block(DefaultDecibelTradingService(aptos, DEPLOYMENT))
    } finally {
      aptos.close()
    }
  }

  private fun order() =
    ValidatedOrder(
      marketAddress = MARKET,
      side = OrderSide.BUY,
      price = 0x0102030405060708uL,
      size = 9uL,
      timeInForce = TimeInForce.IMMEDIATE_OR_CANCEL,
      reduceOnly = false,
      clientOrderId = "order-7",
      stopPrice = null,
      takeProfitTriggerPrice = 10uL,
      takeProfitLimitPrice = 11uL,
      stopLossTriggerPrice = 12uL,
      stopLossLimitPrice = 13uL,
    )
}

private fun TransactionPayload.EntryFunction.argumentHex(): List<String> =
  call.arguments.map { assertIs<MoveArgument.PreSerialized>(it).value.toHex() }

private fun ByteArray.toHex(): String =
  joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

private fun addressHex(value: String): String =
  AccountAddress.fromString(value).toStringLong().removePrefix("0x")

private fun <T> AptosResult<T>.success(): T = assertIs<AptosResult.Success<T>>(this).value

private fun function(name: String, vararg params: String) =
  MoveFunction(
    name = name,
    visibility = MoveVisibility.PRIVATE,
    isEntry = true,
    isView = false,
    genericTypeParams = emptyList(),
    params = listOf("&signer", *params),
    `return` = emptyList(),
  )

private val DEPLOYMENT = DecibelDeployment.Testnet
private const val SUBACCOUNT = "0x11"
private const val MARKET = "0x22"
private const val ASSET = "0x33"
private const val DELEGATE = "0x44"
private const val MAX_U128 = "340282366920938463463374607431768211455"

internal val DECIBEL_ENTRY_ABI =
  MoveModuleBytecode(
    bytecode = "0x",
    abi =
      MoveModule(
        address = DEPLOYMENT.packageAddress,
        name = "dex_accounts_entry",
        friends = emptyList(),
        exposedFunctions =
          listOf(
            function("create_new_subaccount"),
            function(
              "deposit_to_subaccount_at",
              "address",
              "0x1::object::Object<0x1::fungible_asset::Metadata>",
              "u64",
            ),
            function(
              "withdraw_from_cross_collateral",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::dex_accounts::Subaccount>",
              "0x1::object::Object<0x1::fungible_asset::Metadata>",
              "u64",
            ),
            function(
              "delegate_all_trading_to_for_subaccount",
              "address",
              "address",
              "0x1::option::Option<u64>",
            ),
            function(
              "revoke_delegation",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::dex_accounts::Subaccount>",
              "address",
            ),
            function(
              "configure_user_settings_for_market",
              "address",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::perp_market::PerpMarket>",
              "bool",
              "u8",
            ),
            function(
              "place_order_to_subaccount",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::dex_accounts::Subaccount>",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::perp_market::PerpMarket>",
              "u64",
              "u64",
              "bool",
              "u8",
              "bool",
              "0x1::option::Option<0x1::string::String>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<address>",
              "0x1::option::Option<u64>",
            ),
            function(
              "cancel_order_to_subaccount",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::dex_accounts::Subaccount>",
              "u128",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::perp_market::PerpMarket>",
            ),
            function(
              "cancel_tp_sl_order_for_position",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::dex_accounts::Subaccount>",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::perp_market::PerpMarket>",
              "u128",
            ),
            function(
              "place_tp_sl_order_for_position",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::dex_accounts::Subaccount>",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::perp_market::PerpMarket>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<u64>",
              "0x1::option::Option<address>",
              "0x1::option::Option<u64>",
            ),
          ),
        structs = emptyList(),
      ),
  )

internal val DECIBEL_SPOT_ENTRY_ABI =
  MoveModuleBytecode(
    bytecode = "0x",
    abi =
      MoveModule(
        address = DEPLOYMENT.packageAddress,
        name = "dex_accounts_spot_entry",
        friends = emptyList(),
        exposedFunctions =
          listOf(
            function(
              "place_spot_order_to_subaccount",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::dex_accounts::Subaccount>",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::spot_market::SpotMarket>",
              "u64",
              "u64",
              "bool",
              "u8",
              "0x1::option::Option<address>",
              "0x1::option::Option<u64>",
            ),
            function(
              "cancel_spot_order_to_subaccount",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::dex_accounts::Subaccount>",
              "0x1::object::Object<${DEPLOYMENT.packageAddress}::spot_market::SpotMarket>",
              "u128",
            ),
          ),
        structs = emptyList(),
      ),
  )
