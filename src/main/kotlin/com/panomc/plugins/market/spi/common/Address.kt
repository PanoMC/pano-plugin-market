package com.panomc.plugins.market.spi.common

/** A postal / billing address as market hands it to providers; every part is optional (country-dependent). */
class Address(
    val firstName: String?, val lastName: String?, val company: String?, val phone: String?, val email: String?,
    val country: String?, val state: String?, val city: String?, val district: String?, val neighborhood: String?,
    val line1: String?, val line2: String?, val postalCode: String?,
    val taxOffice: String?, val taxNumber: String?, val identityNumber: String?
)
