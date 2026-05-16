package com.macromind.foodscanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ScanHistoryEntity — Room entity for persisting scan results.
 *
 * Each row represents one completed analysis.
 * Timestamps are stored as epoch millis for easy sorting.
 */
@Entity(tableName = "scan_history")
data class ScanHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Epoch millis when the scan was performed */
    val timestamp: Long = System.currentTimeMillis(),

    /** Food category predicted by AI (e.g. "Snacks", "Beverages") */
    val category: String = "",

    /** Health verdict: "Healthy", "Moderate", "Unhealthy" */
    val verdict: String = "",

    /** Verdict confidence 0.0–1.0 */
    val confidence: Float = 0f,

    /** Total additives detected */
    val additiveCount: Int = 0,

    /** Additives with severity >= moderate */
    val harmfulAdditiveCount: Int = 0,

    /** First ~200 chars of scanned ingredient text (for preview) */
    val ingredientPreview: String = "",

    /** Category confidence 0.0–1.0 */
    val categoryConfidence: Float = 0f,

    /** Whether ingredients were scanned */
    val ingredientsScanned: Boolean = false,

    /** Whether nutrition was scanned */
    val nutritionScanned: Boolean = false,

    /** Full serialized AnalysisResult from Gson */
    val rawJson: String = ""
)
