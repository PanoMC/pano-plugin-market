package com.panomc.plugins.market.provider

import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.shipping.CancelShipmentResult
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.SenderKeys
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentView
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.shipping.defaultParcel

/**
 * `manual` (03 section 7): the built-in shipping provider that makes shipping work with no carrier plugin. It is always
 * registered, free and cannot be disabled. There is no network at all: [createShipment] answers
 * `Created(carrierReference = merchantReference)` and the admin types the carrier name and tracking number; the status is
 * set by the admin (events carry `source = MANUAL`). Rates are market's own rule tables (`rateSource = RULES`), the label
 * is market's generic label (`labelFormats` is empty).
 *
 * Settings: the sender address (also the default `from` of every provider, 10 section 8.1), the default parcel
 * dimensions and the generic label paper size. None of them is required: manual shipments work without a sender.
 */
internal class ManualShippingProvider : ShippingProvider {
    override val id: String = ID

    override val descriptor = ProviderDescriptor(
        LocalizedText.of("Manual shipping", "tr" to "Manuel kargo", "ru" to "Ручная доставка"),
        LocalizedText.of(
            "Ship with any carrier yourself: enter the carrier and the tracking number, set the status by hand.",
            "tr" to "Herhangi bir kargo ile kendiniz gönderin: kargo firmasını ve takip numarasını girin, durumu elle güncelleyin.",
            "ru" to "Отправляйте любым перевозчиком сами: укажите перевозчика и трек-номер, статус меняется вручную."
        ),
        "truck"
    )

    override fun settingsSchema(): SettingsSchema = settingsSchema {
        group(GROUP_SENDER, LocalizedText.of("Sender address", "tr" to "Gönderici adresi", "ru" to "Адрес отправителя"))
        group(GROUP_PARCEL, LocalizedText.of("Default parcel", "tr" to "Varsayılan koli", "ru" to "Посылка по умолчанию"))
        group(GROUP_LABEL, LocalizedText.of("Label", "tr" to "Etiket", "ru" to "Этикетка"))

        // The shared helper marks the country and first line required; the manual provider works without a sender,
        // so the same reserved keys are declared here without `required`.
        text(SenderKeys.NAME) { label = LocalizedText.of("Sender name", "tr" to "Gönderici adı", "ru" to "Имя отправителя"); group = GROUP_SENDER }
        text(SenderKeys.COMPANY) { label = LocalizedText.of("Sender company", "tr" to "Gönderici firma", "ru" to "Компания отправителя"); group = GROUP_SENDER }
        text(SenderKeys.PHONE) { label = LocalizedText.of("Sender phone", "tr" to "Gönderici telefonu", "ru" to "Телефон отправителя"); group = GROUP_SENDER }
        text(SenderKeys.EMAIL) { label = LocalizedText.of("Sender email", "tr" to "Gönderici e-postası", "ru" to "Email отправителя"); group = GROUP_SENDER }
        text(SenderKeys.COUNTRY) {
            label = LocalizedText.of("Sender country (ISO code)", "tr" to "Gönderici ülkesi (ISO kodu)", "ru" to "Страна отправителя (код ISO)")
            pattern = "^[A-Za-z]{2}\$"
            placeholder = "TR"
            group = GROUP_SENDER
        }
        text(SenderKeys.STATE) { label = LocalizedText.of("Sender state / province", "tr" to "Gönderici il / eyalet", "ru" to "Регион отправителя"); group = GROUP_SENDER }
        text(SenderKeys.CITY) { label = LocalizedText.of("Sender city", "tr" to "Gönderici şehri", "ru" to "Город отправителя"); group = GROUP_SENDER }
        text(SenderKeys.DISTRICT) { label = LocalizedText.of("Sender district", "tr" to "Gönderici ilçesi", "ru" to "Район отправителя"); group = GROUP_SENDER }
        text(SenderKeys.NEIGHBORHOOD) { label = LocalizedText.of("Sender neighborhood", "tr" to "Gönderici mahallesi", "ru" to "Микрорайон отправителя"); group = GROUP_SENDER }
        text(SenderKeys.LINE1) { label = LocalizedText.of("Sender address line 1", "tr" to "Gönderici adres satırı 1", "ru" to "Адрес отправителя, строка 1"); group = GROUP_SENDER }
        text(SenderKeys.LINE2) { label = LocalizedText.of("Sender address line 2", "tr" to "Gönderici adres satırı 2", "ru" to "Адрес отправителя, строка 2"); group = GROUP_SENDER }
        text(SenderKeys.POSTAL_CODE) { label = LocalizedText.of("Sender postal code", "tr" to "Gönderici posta kodu", "ru" to "Индекс отправителя"); group = GROUP_SENDER }

        defaultParcel(GROUP_PARCEL)

        select(KEY_LABEL_PAPER) {
            label = LocalizedText.of("Label paper size", "tr" to "Etiket kâğıt boyutu", "ru" to "Размер бумаги этикетки")
            default = PAPER_A6
            option(PAPER_A6, LocalizedText.of("A6 (105 x 148 mm)"))
            option(PAPER_A4, LocalizedText.of("A4 (210 x 297 mm)"))
            group = GROUP_LABEL
        }
    }

    override fun capabilities(settings: ProviderSettings): ShippingCapabilities = ShippingCapabilities().also {
        it.rateQuote = false
        it.createShipment = true
        it.labelFormats = emptySet()
        it.cancel = true
        it.trackingPull = false
        it.trackingPush = false
        it.webhookSigned = false
        it.webhookSetup = WebhookSetup.NONE
        it.externalTracking = false
        it.testMode = TestModeSupport.NONE
    }

    /** No network: the reference market sent is the carrier reference; the admin adds carrier name and tracking number. */
    override suspend fun createShipment(ctx: ShippingContext, request: CreateShipmentRequest): CreateShipmentResult =
        CreateShipmentResult.Created(request.merchantReference).also { it.status = ShipmentStatus.IN_TRANSIT }

    override suspend fun cancelShipment(ctx: ShippingContext, shipment: ShipmentView): CancelShipmentResult = CancelShipmentResult.cancelled()

    companion object {
        const val ID = "manual"
        const val KEY_LABEL_PAPER = "labelPaper"
        const val PAPER_A6 = "A6"
        const val PAPER_A4 = "A4"
        private const val GROUP_SENDER = "sender"
        private const val GROUP_PARCEL = "parcel"
        private const val GROUP_LABEL = "label"
    }
}
