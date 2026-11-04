package com.bol.app.data.local.model

/**
 * Projection used by [com.bol.app.data.local.dao.ItemDao.countsByCategory] — each row
 * is one category id and the number of open (not-completed) items in it. Consumed by
 * the daily digest to phrase "5 buy · 3 tasks · 2 watch".
 */
data class CategoryOpenCount(
    val categoryId: String,
    val count: Int,
)
