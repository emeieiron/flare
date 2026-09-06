package xyz.mcxross.flare.decibel.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import xyz.mcxross.flare.decibel.*
import xyz.mcxross.flare.decibel.model.*
import xyz.mcxross.kaptos.*
import xyz.mcxross.kaptos.account.AccountAsset
import xyz.mcxross.kaptos.account.Ed25519Account
import xyz.mcxross.kaptos.core.crypto.Ed25519PrivateKey
import xyz.mcxross.kaptos.model.*
import xyz.mcxross.kaptos.util.APTOS_COIN

/** Opt-in local testnet integration. Keys are read from a private file and never logged. */
class LocalWorkerTransactionTest {
  @Test
  fun accountAndTradingLifecycle() = runBlocking {
    val keyFile = System.getenv("FLARE_TEST_KEY_FILE")
    assumeTrue(!keyFile.isNullOrBlank(), "Set FLARE_TEST_KEY_FILE for local testnet integration")
    val owner =
      Ed25519Account(Ed25519PrivateKey.fromAip80(Files.readString(Path.of(keyFile)).trim()))
    val http = HttpClient {
      install(ContentNegotiation) { json(DecibelClient.DefaultJson) }
      expectSuccess = true
    }
    var token = ""
    val base = "http://127.0.0.1:8787"
    val aptos =
      Aptos(
        AptosConfig(
          network = Network.TESTNET,
          endpoints = AptosEndpoints(fullNode = "$base/aptos/v1"),
          transactionDefaults =
            TransactionDefaults(maxGasAmount = 50_000uL, expirationSecondsFromNow = 60uL),
          fullNode =
            AptosEndpointConfig(
              requestHeaders = {
                mapOf("Authorization" to "Bearer $token", "Origin" to "flare://mobile")
              }
            ),
        )
      )
    suspend fun authenticate(subaccount: String? = null, account: Ed25519Account = owner): String {
      val challenge =
        http
          .post("$base/v1/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(
              buildJsonObject {
                put("network", "testnet")
                put("origin", "flare://mobile")
                put("walletAddress", account.accountAddress.toStringLong())
                subaccount?.let { put("subaccount", it) }
              }
            )
          }
          .body<JsonObject>()
          .getValue("challenge")
          .jsonPrimitive
          .content
      val session =
        http
          .post("$base/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(
              buildJsonObject {
                put("challenge", challenge)
                put("publicKey", account.publicKey.toByteArray().hex())
                put(
                  "signature",
                  account
                    .sign(HexInput.fromByteArray(challenge.encodeToByteArray()))
                    .toByteArray()
                    .hex(),
                )
              }
            )
          }
          .body<JsonObject>()
      token = session.getValue("token").jsonPrimitive.content
      return session.getValue("role").jsonPrimitive.content
    }
    try {
      println("Wallet: ${owner.accountAddress.toStringLong()}")
      println("Session role: ${authenticate()}")
      val ledger = http.get("$base/aptos/v1/") { bearerAuth(token) }.body<JsonObject>()
      assertEquals(
        2,
        ledger.getValue("chain_id").jsonPrimitive.int,
        "Refuse any chain except testnet",
      )
      val client =
        DecibelClient(
          http,
          aptos,
          DecibelConfig(restBaseUrl = "$base/decibel", accessToken = { token }),
        )
      suspend fun execute(command: DecibelCommand, signer: Ed25519Account = owner): String {
        val states =
          client.trading
            .execute(
              signer,
              command,
              externalFeePayer =
                if (System.getenv("FLARE_TEST_SELF_PAY") == "true") null
                else
                  ExternalFeePayerSubmitter { request ->
                    val route = if (signer === owner) "owner" else "trading"
                    val response =
                      http.post("$base/gas/sponsor/$route") {
                        expectSuccess = false
                        bearerAuth(token)
                        header(HttpHeaders.Origin, "flare://mobile")
                        contentType(ContentType.Application.Json)
                        setBody(
                          buildJsonObject {
                            put(
                              "transactionBytes",
                              JsonArray(
                                request.transactionBytes.map {
                                  JsonPrimitive(it.toUByte().toInt())
                                }
                              ),
                            )
                            put(
                              "senderAuth",
                              JsonArray(
                                request.senderAuthenticatorBytes.map {
                                  JsonPrimitive(it.toUByte().toInt())
                                }
                              ),
                            )
                          }
                        )
                      }
                    if (response.status.isSuccess())
                      AptosResult.Success(
                        response
                          .body<JsonObject>()
                          .getValue("transactionHash")
                          .jsonPrimitive
                          .content
                      )
                    else
                      AptosResult.Failure(
                        AptosError.Api(
                          "Sponsorship rejected: ${response.status.value}",
                          errorCode = "gas_station_http_${response.status.value}",
                        )
                      )
                  },
              onPrepared = { hash ->
                println("Prepared ${command::class.simpleName}: $hash")
                Files.writeString(
                  Path.of(keyFile).resolveSibling("flare-testnet-transactions.log"),
                  "${command::class.simpleName}: $hash\n",
                  StandardOpenOption.CREATE,
                  StandardOpenOption.APPEND,
                )
              },
            )
            .toList()
        states.forEach { println("${command::class.simpleName}: $it") }
        return assertIs<TransactionState.Committed>(states.last()).hash
      }
      if (System.getenv("FLARE_TEST_ACTION") == "create") {
        check(client.accounts.subaccounts(owner.accountAddress.toStringLong()).isEmpty()) {
          "An owned account already exists; refusing to create another automatically"
        }
        execute(DecibelCommand.CreateSubaccount)
        delay(2_000)
      }
      val subaccounts = client.accounts.subaccounts(owner.accountAddress.toStringLong())
      println("Owned subaccounts: ${subaccounts.map { it.address }}")
      println(
        "APT balance: ${aptos.accounts.getBalance(owner.accountAddress, AccountAsset.coin(APTOS_COIN))}"
      )
      println(
        "USDC balance: ${aptos.accounts.getBalance(owner.accountAddress,
        AccountAsset.fungibleAsset(DecibelDeployment.Testnet.usdcMetadataAddress),)}"
      )
      val market = client.markets.markets().first { it.name == "BTC/USD" }
      val subaccount = System.getenv("FLARE_TEST_SUBACCOUNT") ?: subaccounts.firstOrNull()?.address
      println("BTC market precision: ${market.precision}")
      if (subaccount != null) {
        println("Scoped session role: ${authenticate(subaccount)}")
        println("Account overview: ${client.accounts.overview(subaccount)}")
        println("Open orders: ${client.accounts.openOrders(subaccount).items.size}")
        println("Delegations: ${client.accounts.delegations(subaccount)}")
      }
      val account = subaccount ?: owner.accountAddress.toStringLong()
      val deployment = DecibelDeployment.Testnet
      val commands =
        listOf(
          DecibelCommand.CreateSubaccount,
          DecibelCommand.Deposit(account, deployment.usdcMetadataAddress, 1uL),
          DecibelCommand.Withdraw(account, deployment.usdcMetadataAddress, 1uL),
          DecibelCommand.DelegateTrading(account, "0x42"),
          DecibelCommand.RevokeDelegation(account, "0x42"),
          DecibelCommand.ConfigureMarket(account, market.address, MarginMode.CROSS, 1u),
          DecibelCommand.CancelOrder(account, market.address, "1"),
          DecibelCommand.CancelPositionTpSl(account, market.address, "1"),
          DecibelCommand.SetPositionTpSl(account, market.address, takeProfitTrigger = 1uL),
          DecibelCommand.PlaceOrder(
            account,
            ValidatedOrder(
              market.address,
              OrderSide.BUY,
              market.minPrice,
              market.minSize,
              TimeInForce.POST_ONLY,
              false,
              null,
              null,
              null,
              null,
              null,
              null,
            ),
          ),
        )
      for (command in commands) {
        assertIs<AptosResult.Success<TransactionPayload.EntryFunction>>(
          client.trading.payload(command)
        )
      }
      println("All 10 command payloads accepted by the deployed testnet ABI")
      if (
        System.getenv("FLARE_TEST_ACTION") in
          setOf(
            "lifecycle",
            "trade",
            "positions",
            "cleanup",
            "protections",
            "resume-protections",
          )
      ) {
        check(subaccount != null) { "Create a test subaccount first" }
        check(client.accounts.openOrders(subaccount).items.isEmpty()) {
          "Test account has existing orders"
        }
        if (System.getenv("FLARE_TEST_ACTION") != "resume-protections")
          check(
            client.accounts.positions(subaccount).none {
              !it.isDeleted && it.size.toBigDecimal().signum() != 0
            }
          ) {
            "Test account has an existing position"
          }
        suspend fun submitOwner(transaction: UnsignedTransaction, operation: String): String {
          val simulation =
            assertIs<AptosResult.Success<List<UserTransactionResponse>>>(
                aptos.transactions.simulate(transaction, owner.publicKey)
              )
              .value
          assertEquals(1, simulation.size)
          assertTrue(simulation.single().success, simulation.single().vmStatus)
          check(simulation.single().gasUnitPrice.toULong() <= 200uL) {
            "Test gas-price cap exceeded"
          }
          val auth =
            assertIs<
                AptosResult.Success<
                  xyz.mcxross.kaptos.transaction.authenticator.AccountAuthenticator
                >
              >(
                aptos.transactions.sign(owner, transaction)
              )
              .value
          val hash =
            assertIs<AptosResult.Success<String>>(
                aptos.transactions.userTransactionHash(transaction, auth)
              )
              .value
          Files.writeString(
            Path.of(keyFile).resolveSibling("flare-testnet-transactions.log"),
            "$operation: $hash\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
          )
          val pending =
            assertIs<AptosResult.Success<PendingTransactionResponse>>(
                aptos.transactions.submit(transaction, auth)
              )
              .value
          assertEquals(hash, pending.hash)
          val committed =
            assertIs<AptosResult.Success<TransactionResponse>>(
                aptos.transactions.waitForTransaction(
                  hash,
                  WaitForTransactionOptions(checkSuccess = false),
                )
              )
              .value
          assertTrue(assertIs<UserTransactionResponse>(committed).success)
          println("$operation committed: $hash")
          return hash
        }
        val collateral = 10_000_000uL
        val asset = AccountAsset.fungibleAsset(deployment.usdcMetadataAddress)
        val walletBalance =
          assertIs<AptosResult.Success<ULong>>(
              aptos.accounts.getBalance(owner.accountAddress, asset)
            )
            .value
        if (
          walletBalance < collateral &&
            System.getenv("FLARE_TEST_ACTION") in setOf("lifecycle", "protections")
        ) {
          val mint =
            assertIs<AptosResult.Success<TransactionPayload.EntryFunction>>(
                aptos.transactions.entryFunctionPayload(
                  "${deployment.packageAddress}::usdc::restricted_mint",
                  arguments = listOf(xyz.mcxross.kaptos.move.MoveArgument.U64(20_000_000uL)),
                )
              )
              .value
          submitOwner(
            assertIs<AptosResult.Success<UnsignedTransaction.Simple>>(
                aptos.transactions.build(owner.accountAddress, mint)
              )
              .value,
            "MINT_TEST_USDC",
          )
        }
        val initialCollateral = client.accounts.overview(subaccount).equityBalance
        if (System.getenv("FLARE_TEST_ACTION") in setOf("lifecycle", "protections")) {
          check(initialCollateral == 0.0) { "Lifecycle requires an empty collateral account" }
          execute(DecibelCommand.Deposit(subaccount, deployment.usdcMetadataAddress, collateral))
        } else if (System.getenv("FLARE_TEST_ACTION") == "resume-protections") {
          check(initialCollateral in 0.1..11.0)
        } else
          check(initialCollateral == 10.0) {
            "Resume expects exactly the previously deposited test collateral"
          }
        val apiPath = Path.of(keyFile).resolveSibling("flare-test-api-key")
        val api =
          if (Files.exists(apiPath))
            Ed25519Account(Ed25519PrivateKey.fromAip80(Files.readString(apiPath).trim()))
          else
            Ed25519Account.generate().also {
              Files.createFile(
                apiPath,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                  java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                ),
              )
              Files.writeString(apiPath, it.privateKey.toAip80().value)
            }
        try {
          println("Independent API wallet: ${api.accountAddress.toStringLong()}")
          if (
            client.accounts.delegations(subaccount).none {
              it.delegate == api.accountAddress.toStringLong() && it.canTradeAllPerpMarkets
            }
          )
            execute(
              DecibelCommand.DelegateTrading(
                subaccount,
                api.accountAddress.toStringLong(),
                (System.currentTimeMillis() / 1000 + 3600).toULong(),
              )
            )
          val apiGas =
            assertIs<AptosResult.Success<ULong>>(
                aptos.accounts.getBalance(api.accountAddress, AccountAsset.coin(APTOS_COIN))
              )
              .value
          if (apiGas < 5_000_000uL)
            submitOwner(
              assertIs<AptosResult.Success<UnsignedTransaction.Simple>>(
                  aptos.coins.buildTransfer(
                    owner.accountAddress,
                    api.accountAddress,
                    10_000_000uL - apiGas,
                  )
                )
                .value,
              "TOP_UP_API_WALLET",
            )
          delay(2_000)
          assertEquals("api", authenticate(subaccount, api))
          if (System.getenv("FLARE_TEST_ACTION") != "resume-protections")
            execute(
              DecibelCommand.ConfigureMarket(subaccount, market.address, MarginMode.CROSS, 1u),
              api,
            )
          if (System.getenv("FLARE_TEST_ACTION") in setOf("lifecycle", "trade")) {
            val book = client.markets.orderBook(market.address)
            val bid =
              DecimalInput(checkNotNull(book.bestBid))
                .toChainUnits("bid", market.priceDecimals)
                .getOrThrow()
            val limit = (bid * 9uL / 10uL / market.tickSize) * market.tickSize
            val id = "flare-" + java.util.UUID.randomUUID().toString().take(20)
            val order =
              OrderDraft(
                  market.address,
                  OrderSide.BUY,
                  OrderType.LIMIT,
                  DecimalInput(market.minSize.toDecimalString(market.sizeDecimals)),
                  limitPrice = DecimalInput(limit.toDecimalString(market.priceDecimals)),
                  timeInForce = TimeInForce.POST_ONLY,
                  clientOrderId = id,
                )
                .validate(market)
            check(order.isValid) { "Invalid test order: ${order.errors}" }
            execute(DecibelCommand.PlaceOrder(subaccount, checkNotNull(order.value)), api)
            var open: Order? = null
            repeat(10) {
              if (open == null) {
                delay(1_000)
                open =
                  client.accounts.openOrders(subaccount).items.firstOrNull {
                    it.clientOrderId == id
                  }
              }
            }
            val placed =
              checkNotNull(open) {
                "Committed placement has not appeared in open orders; inspect the journal before retrying"
              }
            println("Observed resting order: ${placed.orderId}")
            execute(DecibelCommand.CancelOrder(subaccount, market.address, placed.orderId), api)
            var cancelled = false
            repeat(20) {
              if (!cancelled) {
                delay(1_000)
                cancelled =
                  client.accounts.openOrders(subaccount).items.none {
                    it.orderId == placed.orderId
                  }
              }
            }
            assertTrue(cancelled, "Cancellation has not reached REST indexing")
            println("Cancellation verified in open orders")
          }
          if (
            System.getenv("FLARE_TEST_ACTION") in
              setOf("positions", "protections", "resume-protections")
          ) {
            suspend fun marketOrder(
              side: OrderSide,
              size: String,
              reduceOnly: Boolean,
            ): ValidatedOrder {
              val currentBook = client.markets.orderBook(market.address)
              val validated =
                OrderDraft(
                    market.address,
                    side,
                    OrderType.MARKET,
                    DecimalInput(size),
                    reduceOnly = reduceOnly,
                    clientOrderId = "flare-" + java.util.UUID.randomUUID().toString().take(20),
                  )
                  .validate(market, currentBook)
              check(validated.isValid) { "Invalid market test order: ${validated.errors}" }
              return checkNotNull(validated.value)
            }
            if (System.getenv("FLARE_TEST_ACTION") != "resume-protections")
              execute(
                DecibelCommand.PlaceOrder(
                  subaccount,
                  marketOrder(
                    OrderSide.BUY,
                    market.minSize.toDecimalString(market.sizeDecimals),
                    false,
                  ),
                ),
                api,
              )
            var position: Position? = null
            repeat(20) {
              if (position == null) {
                delay(1_000)
                position =
                  client.accounts.positions(subaccount, market.address).firstOrNull {
                    !it.isDeleted && it.size.toBigDecimal().signum() > 0
                  }
              }
            }
            val opened =
              checkNotNull(position) { "Market entry not indexed; inspect before retrying" }
            println("Observed market position: ${opened.size} BTC")
            if (System.getenv("FLARE_TEST_ACTION") in setOf("protections", "resume-protections")) {
              check(
                opened.absoluteSize.toBigDecimal() ==
                  market.minSize.toDecimalString(market.sizeDecimals).toBigDecimal()
              )
              val mark = client.markets.prices(market.address).single().markPrice
              val reference =
                DecimalInput(java.math.BigDecimal.valueOf(mark).toPlainString())
                  .toChainUnits("mark", market.priceDecimals)
                  .getOrThrow()
              val tp = (reference * 11uL / 10uL / market.tickSize) * market.tickSize
              val sl = (reference * 9uL / 10uL / market.tickSize) * market.tickSize
              execute(
                DecibelCommand.SetPositionTpSl(
                  subaccount,
                  market.address,
                  takeProfitTrigger = tp,
                  stopLossTrigger = sl,
                ),
                api,
              )
              var protected: Position? = null
              repeat(20) {
                if (protected == null) {
                  delay(1_000)
                  protected =
                    client.accounts.positions(subaccount, market.address).firstOrNull {
                      it.takeProfitOrderId != null && it.stopLossOrderId != null
                    }
                }
              }
              val protective = checkNotNull(protected) { "Position TP/SL not indexed" }
              execute(
                DecibelCommand.CancelPositionTpSl(
                  subaccount,
                  market.address,
                  checkNotNull(protective.takeProfitOrderId),
                ),
                api,
              )
              execute(
                DecibelCommand.CancelPositionTpSl(
                  subaccount,
                  market.address,
                  checkNotNull(protective.stopLossOrderId),
                ),
                api,
              )
              var removed = false
              repeat(20) {
                if (!removed) {
                  delay(1_000)
                  removed =
                    client.accounts.positions(subaccount, market.address).all {
                      it.takeProfitOrderId == null && it.stopLossOrderId == null
                    }
                }
              }
              assertTrue(removed, "TP/SL cancellation not indexed")
              println("Position TP/SL placement and both cancellations verified")
            }

            execute(
              DecibelCommand.PlaceOrder(
                subaccount,
                marketOrder(OrderSide.SELL, opened.absoluteSize, true),
              ),
              api,
            )
            var closed = false
            repeat(20) {
              if (!closed) {
                delay(1_000)
                closed =
                  client.accounts.positions(subaccount, market.address).none {
                    !it.isDeleted && it.size.toBigDecimal().signum() != 0
                  }
              }
            }
            assertTrue(closed, "Reduce-only close not indexed")
            println("Market entry and reduce-only full close verified")
          }
          assertEquals("owner", authenticate(subaccount))
          execute(DecibelCommand.RevokeDelegation(subaccount, api.accountAddress.toStringLong()))
          delay(2_000)
          assertTrue(
            client.accounts.delegations(subaccount).none {
              it.delegate == api.accountAddress.toStringLong() && it.canTradeAllPerpMarkets
            }
          )
          val revoked =
            assertFailsWith<io.ktor.client.plugins.ClientRequestException> {
              authenticate(subaccount, api)
            }
          assertEquals(HttpStatusCode.Forbidden, revoked.response.status)
          val withdrawable = client.accounts.overview(subaccount).crossWithdrawableBalance
          val withdrawUnits =
            java.math.BigDecimal.valueOf(withdrawable)
              .movePointRight(6)
              .setScale(0, java.math.RoundingMode.DOWN)
              .toPlainString()
              .toULong()
          check(withdrawUnits > 0uL && withdrawUnits <= 11_000_000uL)
          execute(
            DecibelCommand.Withdraw(subaccount, deployment.usdcMetadataAddress, withdrawUnits)
          )
          delay(2_000)
          assertTrue(client.accounts.overview(subaccount).equityBalance < 0.000001)
          println(
            "Lifecycle complete: no open orders, no position, delegation revoked, collateral withdrawn"
          )
        } finally {
          api.clearPrivateKey()
        }
      }
      for (sub in subaccounts) {
        println("Subaccount: $sub")
      }
    } finally {
      try {
        if (token.isNotEmpty())
          http.post("$base/v1/session/revoke") {
            expectSuccess = false
            bearerAuth(token)
            header(HttpHeaders.Origin, "flare://mobile")
          }
      } finally {
        owner.clearPrivateKey()
        aptos.close()
        http.close()
      }
    }
  }
}

private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
