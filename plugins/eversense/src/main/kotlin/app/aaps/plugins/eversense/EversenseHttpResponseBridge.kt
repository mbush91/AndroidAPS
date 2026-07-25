package app.aaps.plugins.eversense

import app.aaps.plugins.eversense.util.EversenseHttp365Util

/**
 * Kotlin can select ExecutorService.submit(Runnable) for the E365 network lambdas, leaving
 * Future.get() typed as Any even though the invoked helper has a concrete response type.
 * Keep the casts centralized and fail immediately if the executor ever returns an unexpected
 * response model.
 */
internal val Any.expires_in: Int
    get() = (this as EversenseHttp365Util.LoginResponseModel).expires_in

internal val Any.access_token: String
    get() = (this as EversenseHttp365Util.LoginResponseModel).access_token

internal val Any.Result: EversenseHttp365Util.FleetSecretV2Result
    get() = (this as EversenseHttp365Util.FleetSecretV2ResponseModel).Result
