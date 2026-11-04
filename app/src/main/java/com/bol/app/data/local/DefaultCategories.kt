package com.bol.app.data.local

import com.bol.app.data.local.entity.CategoryEntity

object DefaultCategories {
    val list = listOf(
        // Display label is "General" (catch-all things to do) but the id
        // stays `tasks` for backward compatibility with stored items and the
        // LLM intent-parser examples. The alias map below keeps both labels
        // routing to the same category.
        //
        // "Reminders" is intentionally NOT a category. Reminders are an action
        // annotation (ItemEntity.reminderTime) that can live on items in any
        // content category — "remind me to buy milk" goes into Buy with a
        // reminder time, not a separate Reminders list. Users see all
        // upcoming reminders in Settings → Scheduled reminders.
        CategoryEntity(id = "tasks", name = "General", icon = "✅", color = "#4A90D9", sortOrder = 0, isDefault = true, description = "general things to do"),
        CategoryEntity(id = "buy", name = "Buy", icon = "🛒", color = "#E85D26", sortOrder = 1, isDefault = true, description = "things to purchase"),
        CategoryEntity(id = "watch", name = "Watch", icon = "📺", color = "#9B59B6", sortOrder = 2, isDefault = true, description = "movies, shows, videos to see"),
        CategoryEntity(id = "ideas", name = "Ideas", icon = "💡", color = "#F1C40F", sortOrder = 3, isDefault = true, description = "thoughts to remember"),
        CategoryEntity(id = "work", name = "Work", icon = "💼", color = "#2D5A3D", sortOrder = 4, isDefault = true, description = "office and job tasks"),
        CategoryEntity(id = "journal", name = "Journal", icon = "📔", color = "#8E44AD", sortOrder = 6, isDefault = true, description = "feelings, emotions, stories with no action"),
    )

    // Color palette for LLM-created categories
    val dynamicColors = listOf(
        "#3498DB", "#1ABC9C", "#D35400", "#16A085",
        "#C0392B", "#2980B9", "#27AE60", "#8E44AD",
        "#F39C12", "#E74C3C", "#1ABC9C", "#34495E"
    )

    // Map LLM category names (and common spoken synonyms) to category IDs.
    // Include both singular and plural forms — the search path hits this map
    // verbatim, so "office task" (singular) needs to resolve the same way
    // "office tasks" does.
    val categoryAliases = mapOf(
        "tasks" to "tasks", "task" to "tasks",
        "to-do" to "tasks", "todo" to "tasks", "todos" to "tasks",
        "chore" to "tasks", "chores" to "tasks",
        "errand" to "tasks", "errands" to "tasks",
        "general" to "tasks",
        "buy" to "buy", "shopping" to "buy",
        "groceries" to "buy", "grocery" to "buy",
        "purchase" to "buy", "purchases" to "buy",
        "shop" to "buy",
        "watch" to "watch", "movies" to "watch", "movie" to "watch",
        "entertainment" to "watch",
        "shows" to "watch", "show" to "watch",
        "films" to "watch", "film" to "watch",
        "ideas" to "ideas", "idea" to "ideas",
        "thoughts" to "ideas", "thought" to "ideas",
        "brainstorm" to "ideas",
        "work" to "work", "office" to "work", "professional" to "work",
        // "reminders" / "reminder" are INTENTIONALLY absent. They are
        // reserved system words (see CategoryRepository.createCategory) —
        // users can't create a category with those names, and if the model ever
        // emits them as a category id, the parser's validIds check rejects
        // it and falls back to "tasks". Reminder semantics live purely on
        // ItemEntity.reminderTime.
        "journal" to "journal", "diary" to "journal",
        "reflection" to "journal", "reflections" to "journal",
        "feelings" to "journal",
    )
}
