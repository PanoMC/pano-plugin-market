package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

/** The requested category move is invalid (e.g. moving a category into its own subtree). */
class InvalidCategoryMove : Error("INVALID_CATEGORY_MOVE", 400)
