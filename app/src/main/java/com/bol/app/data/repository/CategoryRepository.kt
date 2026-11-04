package com.bol.app.data.repository

import com.bol.app.data.local.DefaultCategories
import com.bol.app.data.local.dao.CategoryDao
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.model.CategoryWithCount
import kotlinx.coroutines.flow.Flow

class CategoryRepository(private val categoryDao: CategoryDao) {
    fun getCategoriesWithCounts(): Flow<List<CategoryWithCount>> =
        categoryDao.getCategoriesWithCounts()

    fun getAllActive(): Flow<List<CategoryEntity>> =
        categoryDao.getAllActive()

    suspend fun getById(id: String): CategoryEntity? =
        categoryDao.getById(id)

    suspend fun getAllActiveSnapshot(): List<CategoryEntity> =
        categoryDao.getAllActiveSnapshot()

    suspend fun findByName(name: String): CategoryEntity? =
        categoryDao.findByName(name)

    suspend fun insert(category: CategoryEntity) =
        categoryDao.insert(category)

    suspend fun getActiveCount(): Int =
        categoryDao.getActiveCount()

    /**
     * The task model needs at least one category, and the first-install seed runs fire-and-forget: a kill right after
     * the database is created left none. Inserting the defaults is a no-op for any that exist (IGNORE).
     */
    suspend fun seedDefaultsIfEmpty() {
        if (categoryDao.getActiveCount() == 0) categoryDao.insertAll(DefaultCategories.list)
    }

    /**
     * Create a custom category. Returns error message if validation fails, null on success.
     */
    suspend fun createCategory(name: String, icon: String, description: String): String? {
        if (name.isBlank()) return "Name cannot be empty"
        if (name.length > 30) return "Name too long (max 30 characters)"

        // Reserve "reminders" / "reminder" — these are not content categories
        // in Bol. Reminders are an annotation (ItemEntity.reminderTime) that
        // any item can carry, surfaced via Settings → Scheduled reminders and
        // via voice queries like "what's on my reminder". Allowing a user
        // category with that name would collide with the reminder-intent
        // search path and confuse the product model.
        val lowered = name.trim().lowercase()
        if (lowered == "reminders" || lowered == "reminder") {
            return "Reminders are tracked automatically — check Settings → Scheduled reminders. Pick a different name."
        }

        val activeCount = categoryDao.getActiveCount()
        if (activeCount >= MAX_CATEGORIES) return "Maximum $MAX_CATEGORIES categories reached"

        val existing = categoryDao.findByName(name.trim())
        if (existing != null && existing.isActive) return "Category '${name.trim()}' already exists"

        // If a deactivated category with same name exists, reactivate it
        if (existing != null && !existing.isActive) {
            categoryDao.update(existing.copy(
                isActive = true,
                icon = icon,
                description = description.take(60)
            ))
            return null
        }

        var id = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        // Fallback for non-Latin names (Hindi, etc.) where regex strips everything
        if (id.isBlank()) id = "cat_${System.currentTimeMillis()}"
        // Handle ID collision — append number if ID already exists
        val baseId = id
        var counter = 2
        while (categoryDao.getById(id) != null) {
            id = "${baseId}_$counter"
            counter++
        }
        val nextSortOrder = (categoryDao.getAllActiveSnapshot().maxOfOrNull { it.sortOrder } ?: 0) + 1
        val color = DefaultCategories.dynamicColors[nextSortOrder % DefaultCategories.dynamicColors.size]

        categoryDao.insert(
            CategoryEntity(
                id = id,
                name = name.trim(),
                icon = icon,
                color = color,
                sortOrder = nextSortOrder,
                isDefault = false,
                description = description.take(60)
            )
        )
        return null
    }

    /**
     * Soft-delete a custom category and remove all its items.
     * Default categories cannot be deleted.
     */
    suspend fun deleteCategory(categoryId: String): String? {
        val category = categoryDao.getById(categoryId) ?: return "Category not found"
        if (category.isDefault) return "Cannot delete default categories"
        categoryDao.deleteItemsByCategory(categoryId)
        categoryDao.deactivate(categoryId)
        return null
    }

    companion object {
        const val MAX_CATEGORIES = 10
    }
}
