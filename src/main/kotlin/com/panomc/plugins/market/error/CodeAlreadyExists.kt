package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

/** The code is already used by a gift, coupon or creator code (codes are unique across all three). */
class CodeAlreadyExists : Error("CODE_ALREADY_EXISTS", 409)
