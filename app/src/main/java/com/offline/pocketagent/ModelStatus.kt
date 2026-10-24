package com.offline.pocketagent

enum class ModelReadiness { NOT_IMPORTED, SAVED, LOADING, READY, ERROR }

/** Imported files and native capabilities are separate facts: a saved projector does not imply working vision. */
data class ModelStatus(
    val modelName: String? = null,
    val modelImported: Boolean = false,
    val modelLoaded: Boolean = false,
    val modelLoading: Boolean = false,
    val modelIssue: String? = null,
    val projectorName: String? = null,
    val projectorImported: Boolean = false,
    val visionLoaded: Boolean = false,
    val visionIssue: String? = null,
) {
    val languageState: ModelReadiness get() = when {
        modelLoading -> ModelReadiness.LOADING
        modelLoaded -> ModelReadiness.READY
        modelIssue != null -> ModelReadiness.ERROR
        modelImported -> ModelReadiness.SAVED
        else -> ModelReadiness.NOT_IMPORTED
    }

    val visionState: ModelReadiness get() = when {
        modelLoading && projectorImported -> ModelReadiness.LOADING
        visionLoaded -> ModelReadiness.READY
        projectorImported && modelLoaded && visionIssue != null -> ModelReadiness.ERROR
        projectorImported -> ModelReadiness.SAVED
        else -> ModelReadiness.NOT_IMPORTED
    }

    val canApplyProjectorOnly: Boolean get() = modelImported && !projectorImported && !modelLoading
    val canRetryVision: Boolean get() = modelImported && projectorImported && !visionLoaded && !modelLoading
}
