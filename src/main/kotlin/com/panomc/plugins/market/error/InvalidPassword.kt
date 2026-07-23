package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

/** The admin's re-authentication password (for revealing a stored payment secret) was wrong. */
class InvalidPassword : Error(400)
