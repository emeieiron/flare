package xyz.mcxross.flare.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.kaptos.model.AptosError
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.ExternalFeePayerRequest

interface GasSponsorshipRepository {
  suspend fun submit(
    request: ExternalFeePayerRequest,
    ownerOnly: Boolean,
  ): AptosResult<String>

  suspend fun resolve(fingerprint: String): AptosResult<String?>
}

/**
 * Sends only Kaptos-produced BCS values to Flare's fixed Worker route. The Gas Station credential
 * and upstream URL remain server-side.
 */
class WorkerGasSponsorshipRepository(
  private val client: HttpClient,
  private val runtime: FlareRuntimeConfig,
  private val sessions: SessionRepository,
) : GasSponsorshipRepository {
  override suspend fun submit(
    request: ExternalFeePayerRequest,
    ownerOnly: Boolean,
  ): AptosResult<String> =
    try {
      val route = if (ownerOnly) "owner" else "trading"
      val response =
        client.post("${runtime.workerBaseUrl.trimEnd('/')}/gas/sponsor/$route") {
          bearerAuth(sessions.accessToken())
          header(HttpHeaders.Origin, runtime.appOrigin)
          contentType(ContentType.Application.Json)
          setBody(
            GasStationRequest(
              transactionBytes = request.transactionBytes.toUnsignedInts(),
              senderAuth = request.senderAuthenticatorBytes.toUnsignedInts(),
              additionalSignersAuth =
                request.additionalSignersAuthenticatorBytes
                  .takeIf { it.isNotEmpty() }
                  ?.map(ByteArray::toUnsignedInts),
            )
          )
        }
      if (!response.status.isSuccess()) {
        AptosResult.Failure(
          AptosError.Api(
            message = "Gas sponsorship was not accepted",
            errorCode = "gas_station_http_${response.status.value}",
          )
        )
      } else {
        val hash = response.body<GasStationResponse>().transactionHash
        if (!HASH_PATTERN.matches(hash)) {
          AptosResult.Failure(
            AptosError.Serialization("The Gas Station returned an invalid transaction hash")
          )
        } else {
          AptosResult.Success(hash)
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      AptosResult.Failure(AptosError.Transport("Gas sponsorship request failed", error))
    }

  override suspend fun resolve(fingerprint: String): AptosResult<String?> =
    try {
      if (!HASH_PATTERN.matches(fingerprint)) {
        return AptosResult.Failure(AptosError.Validation("Invalid sponsorship fingerprint"))
      }
      val response =
        client.get("${runtime.workerBaseUrl.trimEnd('/')}/gas/sponsor/status/$fingerprint") {
          bearerAuth(sessions.accessToken())
          header(HttpHeaders.Origin, runtime.appOrigin)
        }
      when (response.status.value) {
        200 -> {
          val hash = response.body<GasStationStatusResponse>().transactionHash
          if (hash != null && HASH_PATTERN.matches(hash)) AptosResult.Success(hash)
          else AptosResult.Failure(AptosError.Serialization("Invalid sponsorship status response"))
        }
        202,
        404 -> AptosResult.Success(null)
        else ->
          AptosResult.Failure(
            AptosError.Api(
              message = "Sponsorship status is unavailable",
              errorCode = "gas_station_status_http_${response.status.value}",
            )
          )
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      AptosResult.Failure(AptosError.Transport("Sponsorship status request failed", error))
    }

  private companion object {
    val HASH_PATTERN = Regex("^0x[0-9a-fA-F]{64}$")
  }
}

@Serializable
private data class GasStationRequest(
  val transactionBytes: List<Int>,
  val senderAuth: List<Int>,
  val additionalSignersAuth: List<List<Int>>? = null,
)

@Serializable private data class GasStationResponse(val transactionHash: String)

@Serializable
private data class GasStationStatusResponse(
  val status: String,
  val transactionHash: String? = null,
)

private fun ByteArray.toUnsignedInts(): List<Int> = map { it.toInt() and 0xff }
