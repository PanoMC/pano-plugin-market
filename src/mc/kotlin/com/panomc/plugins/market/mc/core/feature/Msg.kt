package com.panomc.plugins.market.mc.core.feature

/**
 * Every message key the component uses. `MessagesResourcesTest` checks that each bundled lang file (the files in `mc/lang`)
 * has exactly these keys, so a key can never be missing in one language.
 */
object Msg {
    const val COMMAND_DISABLED = "command.disabled"
    const val COMMAND_PLAYER_ONLY = "command.playerOnly"
    const val COMMAND_NO_PERMISSION = "command.noPermission"
    const val COMMAND_COOLDOWN = "command.cooldown"
    const val COMMAND_UNAVAILABLE = "command.unavailable"

    const val ERROR_NOT_CONNECTED = "error.notConnected"
    const val ERROR_TIMEOUT = "error.timeout"
    const val ERROR_RATE_LIMITED = "error.rateLimited"
    const val ERROR_NOT_READY = "error.notReady"
    const val ERROR_VERSION = "error.versionMismatch"
    const val ERROR_REFUSED = "error.refused"

    const val STORE_LINK = "store.link"
    const val STORE_NO_LINK = "store.noLink"
    const val STORE_USAGE = "store.usage"
    const val STORE_MENU_UNAVAILABLE = "store.menuUnavailable"
    const val HISTORY_HEADER = "history.header"
    const val HISTORY_LINE = "history.line"
    const val HISTORY_EMPTY = "history.empty"

    const val CREDITS_BALANCE = "credits.balance"
    const val CREDITS_UNREGISTERED = "credits.unregistered"
    const val CREDITS_USAGE = "credits.usage"

    const val JOIN_DELIVERED = "join.delivered"
    const val JOIN_GIFT = "join.gift"
    const val JOIN_PENDING = "join.pending"
    const val JOIN_GIFT_PENDING = "join.giftPending"

    const val ADMIN_HELP_HEADER = "admin.help.header"
    const val ADMIN_HELP_CREDITS = "admin.help.credits"
    const val ADMIN_HELP_GRANT = "admin.help.grant"
    const val ADMIN_HELP_PURCHASES = "admin.help.purchases"
    const val ADMIN_HELP_STATUS = "admin.help.status"
    const val ADMIN_HELP_RECOVER = "admin.help.recover"
    const val ADMIN_BAD_AMOUNT = "admin.badAmount"
    const val ADMIN_BAD_PLAYER = "admin.badPlayer"
    const val ADMIN_BAD_PRODUCT = "admin.badProduct"
    const val ADMIN_BAD_QUANTITY = "admin.badQuantity"
    const val ADMIN_GAVE = "admin.creditsGave"
    const val ADMIN_TOOK = "admin.creditsTook"
    const val ADMIN_SET = "admin.creditsSet"
    const val ADMIN_GRANT_DONE = "admin.grantDone"
    const val ADMIN_NO_PERMISSION = "admin.noPermission"
    const val ADMIN_NO_ACCOUNT = "admin.noAccount"
    const val ADMIN_INSUFFICIENT = "admin.insufficientCredits"
    const val ADMIN_CREDITS_OFF = "admin.creditsDisabled"
    const val ADMIN_FAILED = "admin.failed"
    const val ADMIN_UNKNOWN_OUTCOME = "admin.unknownOutcome"
    const val ADMIN_SETTINGS_NOT_LOADED = "admin.settingsNotLoaded"
    const val ADMIN_PURCHASES_HEADER = "admin.purchasesHeader"
    const val ADMIN_PURCHASES_EMPTY = "admin.purchasesEmpty"

    const val STATUS_HEADER = "status.header"
    const val STATUS_COMPONENT = "status.component"
    const val STATUS_CONNECTION_UP = "status.connectionUp"
    const val STATUS_CONNECTION_DOWN = "status.connectionDown"
    const val STATUS_VERSION_UNKNOWN = "status.versionUnknown"
    const val STATUS_VERSION_OK = "status.versionOk"
    const val STATUS_VERSION_REFUSED = "status.versionRefused"
    const val STATUS_QUEUE = "status.queue"
    const val STATUS_STORE_OK = "status.storeOk"
    const val STATUS_STORE_BROKEN = "status.storeBroken"
    const val STATUS_STORE_FAILED = "status.storeFailed"
    const val STATUS_RECOVERY = "status.recovery"
    const val STATUS_SYNC_NEVER = "status.syncNever"
    const val STATUS_SYNC_AGO = "status.syncAgo"
    const val STATUS_CONFIG_LOADED = "status.configLoaded"
    const val STATUS_CONFIG_WAITING = "status.configWaiting"
    const val STATUS_CONFIG_ERROR = "status.configError"

    const val RECOVER_CONSOLE_ONLY = "recover.consoleOnly"
    const val RECOVER_NOT_NEEDED = "recover.notNeeded"
    const val RECOVER_PREVIEW = "recover.preview"
    const val RECOVER_CONFIRM_HINT = "recover.confirmHint"
    const val RECOVER_DONE = "recover.done"

    const val MENU_TITLE = "menu.title"
    const val MENU_LOADING = "menu.loading"
    const val MENU_ALL = "menu.all"
    const val MENU_EMPTY = "menu.empty"
    const val MENU_NAV_PREV = "menu.nav.prev"
    const val MENU_NAV_NEXT = "menu.nav.next"
    const val MENU_NAV_BACK = "menu.nav.back"
    const val MENU_NAV_CLOSE = "menu.nav.close"
    const val MENU_NAV_PAGE = "menu.nav.page"
    const val MENU_PRODUCT_PRICE = "menu.product.price"
    const val MENU_PRODUCT_MONEY = "menu.product.money"
    const val MENU_PRODUCT_STOCK = "menu.product.stock"
    const val MENU_PRODUCT_BUY = "menu.product.buy"
    const val MENU_PRODUCT_WEB = "menu.product.web"
    const val MENU_PRODUCT_UNAVAILABLE = "menu.product.unavailable"
    const val MENU_CONFIRM_TITLE = "menu.confirm.title"
    const val MENU_CONFIRM_BALANCE = "menu.confirm.balance"
    const val MENU_CONFIRM_AFTER = "menu.confirm.after"
    const val MENU_CONFIRM_YES = "menu.confirm.yes"
    const val MENU_CONFIRM_NO = "menu.confirm.no"
    const val MENU_CONFIRM_SHORT = "menu.confirm.short"
    const val MENU_CONFIRM_PROCESSING = "menu.confirm.processing"
    const val MENU_CONFIRM_LEGAL = "menu.confirm.legal"
    const val MENU_BUSY = "menu.busy"
    const val MENU_WEB_LINK = "menu.webLink"
    const val MENU_BOUGHT = "menu.bought"
    const val MENU_UNKNOWN_OUTCOME = "menu.unknownOutcome"
    const val MENU_LEGAL_NEEDED = "menu.legalNeeded"
    const val MENU_REGISTER = "menu.register"
    const val MENU_ERR_BLOCKED = "menu.err.blocked"
    const val MENU_ERR_STOCK = "menu.err.outOfStock"
    const val MENU_ERR_LIMIT = "menu.err.limit"
    const val MENU_ERR_COOLDOWN = "menu.err.cooldown"
    const val MENU_ERR_REQUIREMENT = "menu.err.requirement"
    const val MENU_ERR_NOT_PAYABLE = "menu.err.notPayable"
    const val MENU_ERR_INSUFFICIENT = "menu.err.insufficient"
    const val MENU_ERR_UNAVAILABLE = "menu.err.unavailable"
    const val MENU_ERR_CREDITS_OFF = "menu.err.creditsOff"
    const val MENU_ERR_OTHER = "menu.err.other"

    val ALL: List<String> = listOf(
        COMMAND_DISABLED, COMMAND_PLAYER_ONLY, COMMAND_NO_PERMISSION, COMMAND_COOLDOWN, COMMAND_UNAVAILABLE,
        ERROR_NOT_CONNECTED, ERROR_TIMEOUT, ERROR_RATE_LIMITED, ERROR_NOT_READY, ERROR_VERSION, ERROR_REFUSED,
        STORE_LINK, STORE_NO_LINK, STORE_USAGE, STORE_MENU_UNAVAILABLE, HISTORY_HEADER, HISTORY_LINE, HISTORY_EMPTY,
        CREDITS_BALANCE, CREDITS_UNREGISTERED, CREDITS_USAGE,
        JOIN_DELIVERED, JOIN_GIFT, JOIN_PENDING, JOIN_GIFT_PENDING,
        ADMIN_HELP_HEADER, ADMIN_HELP_CREDITS, ADMIN_HELP_GRANT, ADMIN_HELP_PURCHASES, ADMIN_HELP_STATUS, ADMIN_HELP_RECOVER,
        ADMIN_BAD_AMOUNT, ADMIN_BAD_PLAYER, ADMIN_BAD_PRODUCT, ADMIN_BAD_QUANTITY, ADMIN_GAVE, ADMIN_TOOK, ADMIN_SET, ADMIN_GRANT_DONE,
        ADMIN_NO_PERMISSION, ADMIN_NO_ACCOUNT, ADMIN_INSUFFICIENT, ADMIN_CREDITS_OFF, ADMIN_FAILED, ADMIN_UNKNOWN_OUTCOME, ADMIN_SETTINGS_NOT_LOADED,
        ADMIN_PURCHASES_HEADER, ADMIN_PURCHASES_EMPTY,
        STATUS_HEADER, STATUS_COMPONENT, STATUS_CONNECTION_UP, STATUS_CONNECTION_DOWN, STATUS_VERSION_UNKNOWN, STATUS_VERSION_OK,
        STATUS_VERSION_REFUSED, STATUS_QUEUE, STATUS_STORE_OK, STATUS_STORE_BROKEN, STATUS_STORE_FAILED, STATUS_RECOVERY,
        STATUS_SYNC_NEVER, STATUS_SYNC_AGO, STATUS_CONFIG_LOADED, STATUS_CONFIG_WAITING, STATUS_CONFIG_ERROR,
        RECOVER_CONSOLE_ONLY, RECOVER_NOT_NEEDED, RECOVER_PREVIEW, RECOVER_CONFIRM_HINT, RECOVER_DONE,
        MENU_TITLE, MENU_LOADING, MENU_ALL, MENU_EMPTY, MENU_NAV_PREV, MENU_NAV_NEXT, MENU_NAV_BACK, MENU_NAV_CLOSE, MENU_NAV_PAGE, MENU_PRODUCT_PRICE,
        MENU_PRODUCT_MONEY, MENU_PRODUCT_STOCK, MENU_PRODUCT_BUY, MENU_PRODUCT_WEB, MENU_PRODUCT_UNAVAILABLE, MENU_CONFIRM_TITLE, MENU_CONFIRM_BALANCE,
        MENU_CONFIRM_AFTER, MENU_CONFIRM_YES, MENU_CONFIRM_NO, MENU_CONFIRM_SHORT, MENU_CONFIRM_PROCESSING, MENU_CONFIRM_LEGAL, MENU_BUSY,
        MENU_WEB_LINK, MENU_BOUGHT, MENU_UNKNOWN_OUTCOME, MENU_LEGAL_NEEDED, MENU_REGISTER, MENU_ERR_BLOCKED, MENU_ERR_STOCK, MENU_ERR_LIMIT,
        MENU_ERR_COOLDOWN, MENU_ERR_REQUIREMENT, MENU_ERR_NOT_PAYABLE, MENU_ERR_INSUFFICIENT, MENU_ERR_UNAVAILABLE, MENU_ERR_CREDITS_OFF,
        MENU_ERR_OTHER
    )
}
