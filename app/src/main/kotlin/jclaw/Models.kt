package jclaw

import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Koog 1.2's GoogleModels catalogue stops at gemini-3.5-flash. The API serves 3.6,
 * 3.7 and 3.8 as well, and LLModel is just a data class, so a newer model is six
 * lines rather than a framework upgrade.
 *
 * Worth saying out loud on stage: this is the same gap the eval measures. The
 * framework shipped ten days ago and already trails the models it talks to. You are
 * not blocked by that - you are one declaration away from it.
 *
 * Capabilities and limits mirror the 3.5 profile; adjust if Google publishes
 * different numbers for these.
 */
object Models {

    private fun flash(version: String) = LLModel(
        provider = LLMProvider.Google,
        id = "gemini-$version-flash",
        capabilities = GoogleModels.Gemini3_5Flash.capabilities,
        contextLength = 1_048_576,
        maxOutputTokens = 65_536,
    )

    val Gemini3_6Flash: LLModel = flash("3.6")
    val Gemini3_7Flash: LLModel = flash("3.7")
    val Gemini3_8Flash: LLModel = flash("3.8")

    /**
     * Which flash model the demo runs on. Override with JCLAW_FLASH=3.5 etc.
     *
     * 3.7 by measurement, not by being newest. Same pipeline, same prompts,
     * three runs each: 3.5 = 60s, 3.6 = 37s, 3.7 = ~20s, 3.8 = ~30s. Newer is not
     * automatically faster - 3.8 is consistently slower than 3.7 here - and 3.7 was
     * useful for an interactive demo.
     */
    val flash: LLModel = when (System.getenv("JCLAW_FLASH")) {
        "3.5" -> GoogleModels.Gemini3_5Flash
        "3.6" -> Gemini3_6Flash
        "3.8" -> Gemini3_8Flash
        else -> Gemini3_7Flash
    }

}
