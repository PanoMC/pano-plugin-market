package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

/** Another product already uses this slug. */
class SlugAlreadyExists : Error("SLUG_ALREADY_EXISTS", 409)
