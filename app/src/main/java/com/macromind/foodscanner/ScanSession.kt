package com.macromind.foodscanner

/**
 * ScanSession.kt
 *
 * A singleton that holds scan results across multiple crops/photos
 * within a single scan session.
 *
 * Cleared when the user starts a completely new scan.
 */
object ScanSession {

    enum class ScanMode { INGREDIENTS, NUTRITION }

    var currentMode: ScanMode = ScanMode.INGREDIENTS

    var ingredients: String? = null
    var nutrition: NutritionExtractor.NutritionData? = null
    var historyResult: OfflineAnalyzer.AnalysisResult? = null

    val hasIngredients: Boolean
        get() = !ingredients.isNullOrBlank()

    val hasNutrition: Boolean
        get() = nutrition?.let {
            it.fatG != null || it.carbsG != null ||
                    it.proteinG != null || it.energyKcal != null ||
                    it.saturatedFatG != null || it.sugarsG != null ||
                    it.fiberG != null || it.sodiumMg != null
        } ?: false

    val isComplete: Boolean
        get() = hasIngredients && hasNutrition

    fun clear() {
        ingredients = null
        nutrition = null
        currentMode = ScanMode.INGREDIENTS
    }
}
