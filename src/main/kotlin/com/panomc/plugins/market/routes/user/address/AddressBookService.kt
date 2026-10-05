package com.panomc.plugins.market.routes.user.address

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.shipping.AddressValidator
import com.panomc.plugins.market.core.shipping.Countries
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketAddressDao
import com.panomc.plugins.market.db.dao.MarketCartDao
import com.panomc.plugins.market.db.model.MarketAddress
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.error.ShippingAddressRequired
import com.panomc.plugins.market.spi.common.Address
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * The saved addresses of a buyer (04 section 4, `/api/market/me/addresses`; 10 sections 3.1 and 3.2): list, create, update and
 * delete, at most [MAX_ADDRESSES] per user, one default at most. An address is normalised and validated with the base field set
 * of its country before it is stored (a provider's own extra fields are asked at checkout); the billing-only keys are never stored.
 *
 * Every write takes the row lock of the user's cart first (the same level-0 lock every cart and checkout write takes), so two
 * parallel creates cannot both pass the limit and the default flag stays unique. A foreign or missing id is `404 NOT_FOUND`
 * (never "forbidden": the existence of another user's address is not revealed).
 */
class AddressBookService(
    private val db: MarketDb,
    private val clock: Clock,
    private val addresses: MarketAddressDao,
    private val carts: MarketCartDao
) {
    /** The writable part of a saved address: the `Address` of 01 section 5.6 plus the label and the default flag. */
    class Input(val label: String?, val isDefault: Boolean?, val address: Address)

    /** `GET`: the addresses of [userId], the default one first. */
    suspend fun list(userId: Long): List<JsonObject> = db.tx { conn -> addresses.getByUserId(userId, conn).map { render(it) } }

    /** `POST`: the new id. The first address of a user becomes the default one. */
    suspend fun create(userId: Long, input: Input): Long {
        val clean = validated(input.address)
        val label = labelOf(input.label)

        return db.tx { conn ->
            lock(conn, userId)

            if (addresses.countByUserId(userId, conn) >= MAX_ADDRESSES) throw RequestValueException("addresses", "LIMIT_REACHED")

            val now = clock.now()
            val first = addresses.countByUserId(userId, conn) == 0
            val makeDefault = input.isDefault == true || first
            val id = addresses.add(rowOf(0, userId, label, makeDefault, clean, now, now), conn)

            if (makeDefault) addresses.clearDefault(userId, id, now, conn)

            id
        }
    }

    /**
     * `PUT /:id`: replaces the address (a full `Address` body). `isDefault: true` moves the default flag here; `false` on the
     * current default clears it and the oldest other address (if any) becomes the default; an absent flag keeps it.
     */
    suspend fun update(userId: Long, id: Long, input: Input) {
        val clean = validated(input.address)
        val label = labelOf(input.label)

        db.tx { conn ->
            lock(conn, userId)

            val current = addresses.getById(id, conn)?.takeIf { it.userId == userId } ?: throw NotFound()
            val now = clock.now()
            val makeDefault = input.isDefault ?: current.isDefault

            addresses.update(rowOf(id, userId, label, makeDefault, clean, current.createdAt, now), conn)

            if (makeDefault) {
                addresses.clearDefault(userId, id, now, conn)
            } else if (current.isDefault) {
                promoteOldest(conn, userId, now)
            }
        }
    }

    /** `DELETE /:id`: removes the address; a cart that pointed at it forgets it; deleting the default promotes the oldest other one. */
    suspend fun delete(userId: Long, id: Long) {
        db.tx { conn ->
            val cartId = lock(conn, userId)
            val current = addresses.getById(id, conn)?.takeIf { it.userId == userId } ?: throw NotFound()
            val now = clock.now()

            addresses.deleteById(id, conn)

            if (carts.getById(cartId, conn)?.shippingAddressId == id) carts.updateFields(cartId, mapOf("shippingAddressId" to null), now, conn)

            if (current.isDefault) promoteOldest(conn, userId, now)
        }
    }

    // ---------------------------------------------------------------------------------------------------- internals

    private suspend fun lock(conn: io.vertx.sqlclient.SqlClient, userId: Long): Long {
        val id = carts.ensure(userId, clock.now(), conn)

        carts.getByIdForUpdate(id, conn)

        return id
    }

    private suspend fun promoteOldest(conn: io.vertx.sqlclient.SqlClient, userId: Long, now: Long) {
        val remaining = addresses.getByUserId(userId, conn)

        if (remaining.none { it.isDefault }) remaining.minByOrNull { it.id }?.let { addresses.markDefault(userId, it.id, now, conn) }
    }

    /** Normalises (10 section 3.2) and checks the base set of the address's country (10 section 3.3): 400 `SHIPPING_ADDRESS_REQUIRED {fields}`. */
    private fun validated(raw: Address): Address {
        val normalized = AddressValidator.normalize(raw, keepIdentityNumber = true)

        if (!Countries.isValid(normalized.country)) throw ShippingAddressRequired(listOf("country"))

        val check = AddressValidator.check(normalized)

        if (!check.valid) throw ShippingAddressRequired(check.fields)

        return normalized
    }

    private fun labelOf(raw: String?): String? {
        val label = AddressValidator.clean(raw) ?: return null

        if (label.length > MAX_LABEL) throw RequestValueException("label", "TOO_LONG")

        return label
    }

    private fun rowOf(id: Long, userId: Long, label: String?, isDefault: Boolean, a: Address, createdAt: Long, updatedAt: Long) = MarketAddress(
        id = id, userId = userId, label = label, isDefault = isDefault, firstName = a.firstName, lastName = a.lastName, company = a.company,
        phone = a.phone, email = a.email, country = a.country, state = a.state, city = a.city, district = a.district, neighborhood = a.neighborhood,
        line1 = a.line1, line2 = a.line2, postalCode = a.postalCode, identityNumber = a.identityNumber, type = null, taxOffice = null,
        taxNumber = null, createdAt = createdAt, updatedAt = updatedAt
    )

    companion object {
        const val MAX_ADDRESSES = 10
        const val MAX_LABEL = 64

        /** `Address` + `label`, `isDefault` (04 section 4). */
        fun render(a: MarketAddress): JsonObject {
            val o = JsonObject().put("id", a.id).put("label", a.label).put("isDefault", a.isDefault)

            fun put(key: String, value: String?) {
                if (value != null) o.put(key, value)
            }

            put("firstName", a.firstName)
            put("lastName", a.lastName)
            put("company", a.company)
            put("phone", a.phone)
            put("email", a.email)
            put("country", a.country)
            put("state", a.state)
            put("city", a.city)
            put("district", a.district)
            put("neighborhood", a.neighborhood)
            put("line1", a.line1)
            put("line2", a.line2)
            put("postalCode", a.postalCode)
            put("identityNumber", a.identityNumber)

            return o
        }

        /** Reads the JSON body of POST / PUT: `Address` keys, `label`, `isDefault`; a value of the wrong type is `400 BAD_REQUEST`. */
        fun parse(body: JsonObject): Input {
            fun text(key: String): String? = when (val v = body.getValue(key)) {
                null -> null
                is String -> v
                else -> throw RequestValueException(key, "MUST_BE_TEXT")
            }

            val isDefault = when (val v = body.getValue("isDefault")) {
                null -> null
                is Boolean -> v
                else -> throw RequestValueException("isDefault", "MUST_BE_BOOLEAN")
            }
            val address = Address(
                text("firstName"), text("lastName"), text("company"), text("phone"), text("email"), text("country"), text("state"), text("city"),
                text("district"), text("neighborhood"), text("line1"), text("line2"), text("postalCode"), null, null, text("identityNumber")
            )

            return Input(text("label"), isDefault, address)
        }

        fun renderAll(list: List<JsonObject>): JsonObject = JsonObject().put("addresses", JsonArray(list))
    }
}
