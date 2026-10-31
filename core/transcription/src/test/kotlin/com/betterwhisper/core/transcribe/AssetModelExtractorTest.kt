package com.betterwhisper.core.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class AssetModelExtractorTest {

    @Test
    fun testExtractedModelsDataClass() {
        val dummyPhonon = File("/tmp/phonon")
        val dummyIntent = File("/tmp/intent.gguf")

        val models = ExtractedModels(
            phononDir = dummyPhonon,
            intentModelFile = dummyIntent
        )

        assertEquals(dummyPhonon, models.phononDir)
        assertEquals(dummyIntent, models.intentModelFile)
        assertNotNull(models.intentModelFile)
    }

    @Test
    fun testNullableIntentModel() {
        val dummyPhonon = File("/tmp/phonon")
        val models = ExtractedModels(
            phononDir = dummyPhonon,
            intentModelFile = null
        )
        assertEquals(dummyPhonon, models.phononDir)
        assertEquals(null, models.intentModelFile)
    }
}
