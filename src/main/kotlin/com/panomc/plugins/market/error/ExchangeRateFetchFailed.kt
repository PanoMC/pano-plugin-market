package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

/** The exchange rate provider could not be reached or returned no usable rate. */
class ExchangeRateFetchFailed : Error(502)
