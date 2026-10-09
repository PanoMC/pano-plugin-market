package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

/** The payment method can't be enabled until all of its required fields are filled in. */
class PaymentMethodNotConfigured : Error("PAYMENT_METHOD_NOT_CONFIGURED", 400)
