package com.panomc.plugins.market.spi.shipping

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchemaBuilder

/**
 * Reserved settings keys of the sender address and the default parcel (03 section 5): market reads them to fill
 * `QuoteRequest.from` / `CreateShipmentRequest.from` without knowing the provider.
 */
object SenderKeys {
    const val NAME = "senderName"
    const val COMPANY = "senderCompany"
    const val PHONE = "senderPhone"
    const val EMAIL = "senderEmail"
    const val COUNTRY = "senderCountry"
    const val STATE = "senderState"
    const val CITY = "senderCity"
    const val DISTRICT = "senderDistrict"
    const val NEIGHBORHOOD = "senderNeighborhood"
    const val LINE1 = "senderLine1"
    const val LINE2 = "senderLine2"
    const val POSTAL_CODE = "senderPostalCode"
    const val PARCEL_LENGTH_MM = "defaultParcelLengthMm"
    const val PARCEL_WIDTH_MM = "defaultParcelWidthMm"
    const val PARCEL_HEIGHT_MM = "defaultParcelHeightMm"

    /** The sender fields in declaration order. */
    val ADDRESS: List<String> = listOf(NAME, COMPANY, PHONE, EMAIL, COUNTRY, STATE, CITY, DISTRICT, NEIGHBORHOOD, LINE1, LINE2, POSTAL_CODE)

    val PARCEL: List<String> = listOf(PARCEL_LENGTH_MM, PARCEL_WIDTH_MM, PARCEL_HEIGHT_MM)
}

private fun lt(en: String, tr: String, ru: String) = LocalizedText.of(en, "tr" to tr, "ru" to ru)

/** Declares exactly the sender address fields of [SenderKeys.ADDRESS] (country and first address line required). */
fun SettingsSchemaBuilder.senderAddress(group: String? = null) {
    val g = group
    text(SenderKeys.NAME) { label = lt("Sender name", "Gönderici adı", "Имя отправителя"); this.group = g }
    text(SenderKeys.COMPANY) { label = lt("Sender company", "Gönderici firma", "Компания отправителя"); this.group = g }
    text(SenderKeys.PHONE) { label = lt("Sender phone", "Gönderici telefonu", "Телефон отправителя"); this.group = g }
    text(SenderKeys.EMAIL) { label = lt("Sender email", "Gönderici e-postası", "Email отправителя"); this.group = g }
    text(SenderKeys.COUNTRY) {
        label = lt("Sender country (ISO code)", "Gönderici ülkesi (ISO kodu)", "Страна отправителя (код ISO)")
        required = true
        pattern = "^[A-Za-z]{2}\$"
        placeholder = "TR"
        this.group = g
    }
    text(SenderKeys.STATE) { label = lt("Sender state / province", "Gönderici il / eyalet", "Регион отправителя"); this.group = g }
    text(SenderKeys.CITY) { label = lt("Sender city", "Gönderici şehri", "Город отправителя"); this.group = g }
    text(SenderKeys.DISTRICT) { label = lt("Sender district", "Gönderici ilçesi", "Район отправителя"); this.group = g }
    text(SenderKeys.NEIGHBORHOOD) { label = lt("Sender neighborhood", "Gönderici mahallesi", "Микрорайон отправителя"); this.group = g }
    text(SenderKeys.LINE1) { label = lt("Sender address line 1", "Gönderici adres satırı 1", "Адрес отправителя, строка 1"); required = true; this.group = g }
    text(SenderKeys.LINE2) { label = lt("Sender address line 2", "Gönderici adres satırı 2", "Адрес отправителя, строка 2"); this.group = g }
    text(SenderKeys.POSTAL_CODE) { label = lt("Sender postal code", "Gönderici posta kodu", "Индекс отправителя"); this.group = g }
}

/** Declares exactly the default parcel fields of [SenderKeys.PARCEL] (millimetres). */
fun SettingsSchemaBuilder.defaultParcel(group: String? = null) {
    val g = group
    number(SenderKeys.PARCEL_LENGTH_MM) {
        label = lt("Default parcel length (mm)", "Varsayılan koli uzunluğu (mm)", "Длина посылки по умолчанию (мм)")
        min = 1
        max = 10_000
        this.group = g
    }
    number(SenderKeys.PARCEL_WIDTH_MM) {
        label = lt("Default parcel width (mm)", "Varsayılan koli genişliği (mm)", "Ширина посылки по умолчанию (мм)")
        min = 1
        max = 10_000
        this.group = g
    }
    number(SenderKeys.PARCEL_HEIGHT_MM) {
        label = lt("Default parcel height (mm)", "Varsayılan koli yüksekliği (mm)", "Высота посылки по умолчанию (мм)")
        min = 1
        max = 10_000
        this.group = g
    }
}

/** Reads the reserved sender keys of provider settings. */
object SenderAddress {
    /** The sender address, or null when the country or the first address line is missing. */
    fun from(settings: ProviderSettings): Address? {
        val country = settings.string(SenderKeys.COUNTRY)?.uppercase() ?: return null
        val line1 = settings.string(SenderKeys.LINE1) ?: return null
        return Address(
            firstName = settings.string(SenderKeys.NAME), lastName = null, company = settings.string(SenderKeys.COMPANY),
            phone = settings.string(SenderKeys.PHONE), email = settings.string(SenderKeys.EMAIL), country = country,
            state = settings.string(SenderKeys.STATE), city = settings.string(SenderKeys.CITY),
            district = settings.string(SenderKeys.DISTRICT), neighborhood = settings.string(SenderKeys.NEIGHBORHOOD),
            line1 = line1, line2 = settings.string(SenderKeys.LINE2), postalCode = settings.string(SenderKeys.POSTAL_CODE),
            taxOffice = null, taxNumber = null, identityNumber = null
        )
    }

    /** Default parcel dimensions (length, width, height in mm), each null when unset. */
    fun defaultParcelMm(settings: ProviderSettings): Triple<Int?, Int?, Int?> = Triple(
        settings.long(SenderKeys.PARCEL_LENGTH_MM)?.toInt(),
        settings.long(SenderKeys.PARCEL_WIDTH_MM)?.toInt(),
        settings.long(SenderKeys.PARCEL_HEIGHT_MM)?.toInt()
    )
}
