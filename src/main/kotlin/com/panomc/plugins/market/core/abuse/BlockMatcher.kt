package com.panomc.plugins.market.core.abuse

/** One non-deleted row of `market_block`. `expiresAt` is epoch ms or null. */
data class BlockEntry(val id: Long, val type: BlockType, val value: String, val expiresAt: Long? = null)

/** What a request is checked against (11 section 9.2). Usernames and e-mails may be in any case. */
class BlockSubjects(
    val usernames: Set<String> = emptySet(),
    val userIds: Set<Long> = emptySet(),
    val emails: Set<String> = emptySet(),
    val ip: String? = null,
)

/**
 * Pure matcher over a snapshot of block rows. First hit wins in the order USER, PLAYER, EMAIL, IP; rows with
 * `expiresAt <= now` are ignored; IP uses the full address against the stored range.
 */
class BlockMatcher(entries: List<BlockEntry>) {
    private val byType: Map<BlockType, List<BlockEntry>> = entries.groupBy { it.type }
    private val ranges: List<Pair<BlockEntry, IpRange>> =
        (byType[BlockType.IP] ?: emptyList()).mapNotNull { e -> IpRange.parse(e.value)?.let { e to it } }

    fun match(subjects: BlockSubjects, now: Long): BlockEntry? {
        fun live(e: BlockEntry) = e.expiresAt == null || e.expiresAt > now

        val ids = subjects.userIds.map { it.toString() }.toSet()
        byType[BlockType.USER]?.firstOrNull { live(it) && it.value in ids }?.let { return it }

        val names = subjects.usernames.map { it.lowercase() }.toSet()
        byType[BlockType.PLAYER]?.firstOrNull { live(it) && it.value.lowercase() in names }?.let { return it }

        val emails = subjects.emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val domains = emails.mapNotNull { BlockValue.domainOf(it)?.let { d -> "@$d" } }.toSet()
        byType[BlockType.EMAIL]?.firstOrNull { live(it) && (it.value.lowercase() in emails || it.value.lowercase() in domains) }
            ?.let { return it }

        val ip = subjects.ip
        if (ip != null) ranges.firstOrNull { (e, r) -> live(e) && r.contains(ip) }?.let { return it.first }
        return null
    }
}
