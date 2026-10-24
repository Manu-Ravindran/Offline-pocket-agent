package com.offline.pocketagent

import org.junit.Assert.*
import org.junit.Test

class ModelStatusTest {
    @Test fun emptySetupDoesNotClaimAnyImportedOrReadyModel() {
        val status = ModelStatus()
        assertEquals(ModelReadiness.NOT_IMPORTED, status.languageState)
        assertEquals(ModelReadiness.NOT_IMPORTED, status.visionState)
        assertFalse(status.canApplyProjectorOnly)
        assertFalse(status.canRetryVision)
    }

    @Test fun savedFilesRemainSavedUntilTheNativeRuntimeLoadsThem() {
        val status = ModelStatus(
            modelName = "Qwen3.5-4B-Q4_K_M.gguf", modelImported = true,
            projectorName = "mmproj-F16.gguf", projectorImported = true,
        )
        assertEquals(ModelReadiness.SAVED, status.languageState)
        assertEquals(ModelReadiness.SAVED, status.visionState)
        assertFalse(status.modelLoaded)
        assertFalse(status.visionLoaded)
        assertTrue(status.canRetryVision)
    }

    @Test fun importedProjectorWithFailedActivationIsNeverShownAsReady() {
        val nativeReason = "The text model is ready, but its vision projector could not be activated."
        val status = ModelStatus(
            modelName = "language.gguf", modelImported = true, modelLoaded = true,
            projectorName = "vision.gguf", projectorImported = true,
            visionLoaded = false, visionIssue = nativeReason,
        )
        assertEquals(ModelReadiness.READY, status.languageState)
        assertEquals(ModelReadiness.ERROR, status.visionState)
        assertEquals(nativeReason, status.visionIssue)
        assertTrue(status.canRetryVision)
        assertFalse(status.canApplyProjectorOnly)
    }

    @Test fun textReadyWithoutAProjectorAllowsTheMissingFileToBeAddedSeparately() {
        val status = ModelStatus(modelImported = true, modelLoaded = true, visionIssue = "No vision file imported.")
        assertEquals(ModelReadiness.READY, status.languageState)
        assertEquals(ModelReadiness.NOT_IMPORTED, status.visionState)
        assertTrue(status.canApplyProjectorOnly)
        assertFalse(status.canRetryVision)
    }

    @Test fun setupInProgressBlocksConflictingApplyAndRetryActions() {
        val status = ModelStatus(modelImported = true, modelLoaded = true, modelLoading = true, projectorImported = true)
        assertEquals(ModelReadiness.LOADING, status.languageState)
        assertEquals(ModelReadiness.LOADING, status.visionState)
        assertFalse(status.canApplyProjectorOnly)
        assertFalse(status.canRetryVision)
    }

    @Test fun aLoadErrorKeepsSavedFileNamesVisibleWithoutClaimingLoadedModels() {
        val status = ModelStatus(
            modelName = "language.gguf", modelImported = true,
            projectorName = "vision.gguf", projectorImported = true,
            modelIssue = "Native model initialization failed.",
        )
        assertEquals(ModelReadiness.ERROR, status.languageState)
        assertEquals("language.gguf", status.modelName)
        assertEquals("vision.gguf", status.projectorName)
        assertFalse(status.modelLoaded)
        assertFalse(status.visionLoaded)
    }

    @Test fun anActuallyActivatedPairShowsBothCapabilitiesReady() {
        val status = ModelStatus(
            modelImported = true, modelLoaded = true,
            projectorImported = true, visionLoaded = true,
        )
        assertEquals(ModelReadiness.READY, status.languageState)
        assertEquals(ModelReadiness.READY, status.visionState)
        assertFalse(status.canApplyProjectorOnly)
        assertFalse(status.canRetryVision)
    }
}
