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

    val ALL: List<String> = listOf(
        COMMAND_DISABLED, COMMAND_PLAYER_ONLY, COMMAND_NO_PERMISSION, COMMAND_COOLDOWN, COMMAND_UNAVAILABLE,
        ERROR_NOT_CONNECTED, ERROR_TIMEOUT, ERROR_RATE_LIMITED, ERROR_NOT_READY, ERROR_VERSION, ERROR_REFUSED,
        STORE_LINK, STORE_NO_LINK, STORE_USAGE, STORE_MENU_UNAVAILABLE, HISTORY_HEADER, HISTORY_LINE, HISTORY_EMPTY,
        CREDITS_BALANCE, CREDITS_UNREGISTERED, CREDITS_USAGE,
        JOIN_DELIVERED, JOIN_GIFT, JOIN_PENDING, JOIN_GIFT_PENDING,
        ADMIN_HELP_HEADER, ADMIN_HELP_CREDITS, ADMIN_HELP_GRANT, ADMIN_HELP_PURCHASES, ADMIN_HELP_STATUS, ADMIN_HELP_RECOVER,
        ADMIN_BAD_AMOUNT, ADMIN_BAD_PLAYER, ADMIN_BAD_PRODUCT, ADMIN_BAD_QUANTITY, ADMIN_GAVE, ADMIN_TOOK, ADMIN_SET, ADMIN_GRANT_DONE,
        ADMIN_NO_PERMISSION, ADMIN_NO_ACCOUNT, ADMIN_INSUFFICIENT, ADMIN_CREDITS_OFF, ADMIN_FAILED, ADMIN_UNKNOWN_OUTCOME,
        ADMIN_PURCHASES_HEADER, ADMIN_PURCHASES_EMPTY,
        STATUS_HEADER, STATUS_COMPONENT, STATUS_CONNECTION_UP, STATUS_CONNECTION_DOWN, STATUS_VERSION_UNKNOWN, STATUS_VERSION_OK,
        STATUS_VERSION_REFUSED, STATUS_QUEUE, STATUS_STORE_OK, STATUS_STORE_BROKEN, STATUS_STORE_FAILED, STATUS_RECOVERY,
        STATUS_SYNC_NEVER, STATUS_SYNC_AGO, STATUS_CONFIG_LOADED, STATUS_CONFIG_WAITING, STATUS_CONFIG_ERROR,
        RECOVER_CONSOLE_ONLY, RECOVER_NOT_NEEDED, RECOVER_PREVIEW, RECOVER_CONFIRM_HINT, RECOVER_DONE
    )
}
