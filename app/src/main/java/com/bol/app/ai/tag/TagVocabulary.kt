package com.bol.app.ai.tag

/**
 * Closed vocabulary for semantic item tags. The same set is used at BOTH
 * index-time tagging AND query-time expansion, so a query that maps to
 * `outdoor` will match items tagged `outdoor` — the synonym problem is
 * eliminated by construction.
 *
 * Organized into 5 axes. Each item should be tagged with 3-6 tags drawn
 * from multiple axes (e.g. one Location + one Nature + a Domain + an
 * Activity). Multi-axis tagging gives robust matching: even if a query
 * hits only one axis, the item surfaces.
 *
 * Revisit this vocabulary quarterly. If users ask things our tags can't
 * express, add new ones — but keep the total under ~60 so the model can
 * actually hold the list in its prompt and pick reliably.
 */
object TagVocabulary {

    /** Where the activity happens. */
    val LOCATION = listOf(
        "outdoor", "indoor", "errand", "home", "office", "travel",
    )

    /** What kind of thing it is. */
    val NATURE = listOf(
        "chore", "purchase", "entertainment", "learning", "social",
        "wellness", "finance", "reminder",
    )

    /** Domain / subject area. */
    val DOMAIN = listOf(
        "food", "vehicle", "clothing", "household", "family",
        "health", "tech", "creative", "work",
    )

    /** Mode of engagement. */
    val ACTIVITY = listOf(
        "physical", "mental", "reading", "watching", "cooking",
        "writing", "music", "exercise",
    )

    /** Temporal character. */
    val TIME = listOf(
        "urgent", "routine", "quick", "long", "recurring", "oneoff",
    )

    /** Full canonical set — what the model is allowed to output. */
    val ALL: Set<String> = (LOCATION + NATURE + DOMAIN + ACTIVITY + TIME).toSet()

    /**
     * Small alias map — maps common near-synonyms the model might output to
     * canonical forms. Applied as a safety net after tag post-filtering.
     * Keep this tiny and obvious; don't let it sprawl.
     */
    val ALIASES: Map<String, String> = mapOf(
        // location
        "outside" to "outdoor",
        "outdoors" to "outdoor",
        "inside" to "indoor",
        "indoors" to "indoor",
        // purchase
        "shopping" to "purchase",
        "buy" to "purchase",
        "buying" to "purchase",
        "shop" to "purchase",
        // watching
        "watch" to "watching",
        "watchlist" to "watching",
        "movie" to "watching",
        "movies" to "watching",
        "film" to "watching",
        "films" to "watching",
        "show" to "watching",
        "shows" to "watching",
        "series" to "watching",
        "tv" to "watching",
        "binge" to "watching",
        "stream" to "watching",
        "streaming" to "watching",
        "ott" to "watching",
        "netflix" to "watching",
        "prime" to "watching",
        // exercise
        "workout" to "exercise",
        "fitness" to "exercise",
        "gym" to "exercise",
        "running" to "exercise",
        "yoga" to "exercise",
        // finance
        "money" to "finance",
        "bill" to "finance",
        "bills" to "finance",
        "pay" to "finance",
        "payment" to "finance",
        "rent" to "finance",
        "tax" to "finance",
        "taxes" to "finance",
        // food
        "eat" to "food",
        "eating" to "food",
        "meal" to "food",
        "meals" to "food",
        "groceries" to "food",
        "grocery" to "food",
        "dinner" to "food",
        "lunch" to "food",
        "breakfast" to "food",
        "khana" to "food",
        "subzi" to "food",
        "sabzi" to "food",
        "roti" to "food",
        "chai" to "food",
        // vehicle
        "car" to "vehicle",
        "bike" to "vehicle",
        "scooter" to "vehicle",
        "gaadi" to "vehicle",
        "auto" to "vehicle",
        // clothing
        "dress" to "clothing",
        "clothes" to "clothing",
        "saree" to "clothing",
        "shirt" to "clothing",
        "kurta" to "clothing",
        "jeans" to "clothing",
        // family
        "mom" to "family",
        "mummy" to "family",
        "maa" to "family",
        "papa" to "family",
        "dad" to "family",
        "pita" to "family",
        "kids" to "family",
        "bachche" to "family",
        "bachcha" to "family",
        "beta" to "family",
        "beti" to "family",
        "bhai" to "family",
        "behen" to "family",
        // health
        "doctor" to "health",
        "medicine" to "health",
        "dava" to "health",
        "medical" to "health",
        "checkup" to "health",
        "dentist" to "health",
        // time/effort
        "fast" to "quick",
        "short" to "quick",
        "soon" to "urgent",
        "now" to "urgent",
        "today" to "urgent",
        "tomorrow" to "urgent",
        "asap" to "urgent",
        "daily" to "routine",
        "weekly" to "routine",
        // chore/errand
        "task" to "chore",
        "tasks" to "chore",
        "todo" to "chore",
        "to-do" to "chore",
        "chores" to "chore",
        "errands" to "errand",
        "wash" to "chore",
        "washing" to "chore",
        "cleaning" to "chore",
        "clean" to "chore",
        // work
        "meeting" to "work",
        "meetings" to "work",
        "office" to "office",
        "job" to "work",
        "project" to "work",
        "deadline" to "work",
        "interview" to "work",
        // social/family
        "call" to "social",
        "phone" to "social",
        "message" to "social",
        "text" to "social",
        // entertainment
        "game" to "entertainment",
        "gaming" to "entertainment",
        "book" to "reading",
        "books" to "reading",
        "read" to "reading",
        "reading" to "reading",
        // activity
        "hike" to "outdoor",
        "hiking" to "outdoor",
        "walk" to "outdoor",
        "walking" to "outdoor",
        "garden" to "outdoor",
        "gardening" to "outdoor",
        "yard" to "outdoor",
        "park" to "outdoor",
        "cricket" to "outdoor",
        "football" to "outdoor",
    )

    /**
     * Derive canonical tags directly from a query string by tokenizing and
     * running each word through [canonicalize]. Used at query time so the
     * tag lane still fires even when the model-based query expansion is skipped.
     */
    fun tagsFromQuery(query: String): List<String> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val tags = LinkedHashSet<String>()
        for (w in words) canonicalize(w)?.let { tags.add(it) }
        return tags.toList()
    }

    /**
     * Normalize a raw tag from the model: lowercase, strip punctuation, then
     * alias-resolve, then validate against the vocabulary. Returns null
     * for tags that can't be canonicalized — the caller drops those.
     */
    fun canonicalize(raw: String): String? {
        val clean = raw.trim().lowercase().trim('.', ',', ';', ':', '!', '?', '"', '\'')
        if (clean.isEmpty()) return null
        val resolved = ALIASES[clean] ?: clean
        return if (resolved in ALL) resolved else null
    }

    /** Human-readable prompt fragment listing the vocabulary. */
    val PROMPT_LIST: String = buildString {
        append("Pick tags ONLY from this list (do not invent new ones):\n")
        append("- Location: ${LOCATION.joinToString(", ")}\n")
        append("- Nature: ${NATURE.joinToString(", ")}\n")
        append("- Domain: ${DOMAIN.joinToString(", ")}\n")
        append("- Activity: ${ACTIVITY.joinToString(", ")}\n")
        append("- Time: ${TIME.joinToString(", ")}")
    }
}
