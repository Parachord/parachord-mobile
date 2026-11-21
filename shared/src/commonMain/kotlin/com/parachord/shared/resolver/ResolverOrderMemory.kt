package com.parachord.shared.resolver

/**
 * The user's whole resolver ranking, disabled plug-ins included
 * (`resolver_order_memory`), a port of desktop's `rememberOrder` /
 * `insertRemembered` (`src/renderer/lib/resolver-order.js`). Turning a
 * plug-in off and on again puts it back where the user ranked it, not at its
 * canonical slot or the end of the list.
 */
object ResolverOrderMemory {
    /**
     * The memory after the active [order] changed (a drag): the active ones in
     * [order]'s order, each id [memory] holds that [order] doesn't kept right
     * after the one it followed (first when it led).
     */
    fun rememberOrder(memory: List<String>?, order: List<String>): List<String> {
        val out = order.toMutableList()
        var prevId: String? = null
        for (id in memory.orEmpty()) {
            if (id in out) { prevId = id; continue }
            val at = if (prevId == null) 0 else out.indexOf(prevId) + 1
            out.add(at, id)
            prevId = id
        }
        return out
    }

    /**
     * [order] with [id] back where [memory] ranks it: before the first id in
     * [order] the memory ranks below it. One never ranked goes to its canonical
     * place ([ResolverScoring.CANONICAL_RESOLVER_ORDER]). [order] itself when
     * it already holds [id].
     */
    fun insertRemembered(order: List<String>, id: String, memory: List<String>?): List<String> {
        if (id in order) return order
        val mem = memory.orEmpty()
        val at = mem.indexOf(id)
        if (at == -1) return insertInCanonicalOrder(order, id)
        var insertAt = order.size
        for (i in order.indices) {
            val p = mem.indexOf(order[i])
            if (p != -1 && p > at) { insertAt = i; break }
        }
        return order.subList(0, insertAt) + id + order.subList(insertAt, order.size)
    }

    /** Desktop's `insertInCanonicalOrder`. */
    fun insertInCanonicalOrder(order: List<String>, newId: String): List<String> {
        if (newId in order) return order
        val canonical = ResolverScoring.CANONICAL_RESOLVER_ORDER
        val canonicalIndex = canonical.indexOf(newId)
        if (canonicalIndex == -1) return order + newId
        var insertAt = order.size
        for (i in order.indices) {
            val existing = canonical.indexOf(order[i])
            if (existing != -1 && existing > canonicalIndex) { insertAt = i; break }
        }
        return order.subList(0, insertAt) + newId + order.subList(insertAt, order.size)
    }
}
