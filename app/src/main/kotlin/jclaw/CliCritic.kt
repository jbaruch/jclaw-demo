package jclaw

import ai.koog.agents.cli.CliAIAgent
import ai.koog.agents.cli.CliAgentStructuredResponse
import ai.koog.agents.cli.claude.ClaudePermissionMode
import ai.koog.agents.cli.transport.ProcessCliTransport
import jclaw.domain.DeclineCritique
import jclaw.domain.DeclineDeployment
import jclaw.domain.DeclineRequest
import jclaw.domain.Scenario
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/** Gemini identifies the obligation; subscription CLIs draft, refine, and judge. */
object CliCritic {
    private val claudeFlags = listOf(
        "--safe-mode", "--strict-mcp-config", "--tools=",
        "--no-session-persistence", "--settings", """{"forceLoginMethod":"claudeai"}""",
    )

    fun claudeDrafter(): CliAIAgent<DeclineRequest, CliAgentStructuredResponse<DeclineDeployment>> =
        CliAIAgent.claude(
            transport = SubscriptionCliTransport,
            outputClass = DeclineDeployment::class,
            apiKey = null,
            name = "claude-drafter",
            workspace = cliWorkspace("claude-draft").toString(),
            timeout = 3.minutes,
            permissionMode = ClaudePermissionMode.DontAsk,
            additionalFlags = claudeFlags,
            systemPrompt = Scenario.SYSTEM_PROMPT,
            generateRequest = { request: DeclineRequest ->
                """
                Draft the best plan to get Baruch out of this obligation. Choose a flavor,
                write the message to the organizer, and write a brief hallway script.
                Account for the request's recently used flavors and known attendees.
                You are drafting only: no calendar event has been created, so set
                fakeCalendarEventId to null. Do not claim you took any external action.

                CONTEXT: ${Scenario.USER_CONTEXT}
                OBLIGATION: ${Scenario.EVENT_TITLE}
                REQUEST: $request
                """.trimIndent()
            },
        )

    fun claudeRefiner(): CliAIAgent<String, CliAgentStructuredResponse<DeclineDeployment>> =
        CliAIAgent.claude(
            transport = SubscriptionCliTransport,
            outputClass = DeclineDeployment::class,
            apiKey = null,
            name = "claude-refiner",
            workspace = cliWorkspace("claude-refine").toString(),
            timeout = 3.minutes,
            permissionMode = ClaudePermissionMode.DontAsk,
            additionalFlags = claudeFlags,
            systemPrompt = Scenario.SYSTEM_PROMPT,
            generateRequest = { feedback: String ->
                """
                Revise the proposed plan using the judge's feedback. Return the complete
                revised plan. No calendar event has been created, so set
                fakeCalendarEventId to null. Do not claim you took any external action.

                CONTEXT: ${Scenario.USER_CONTEXT}
                OBLIGATION: ${Scenario.EVENT_TITLE}
                PREVIOUS PLAN AND FEEDBACK:
                $feedback
                """.trimIndent()
            },
        )

    internal const val CODEX_SYSTEM_PROMPT = "You are an independent reviewer of a proposed plan. " +
        "Assess its quality and return the requested structured result."

    internal fun codexRequest(deployment: DeclineDeployment): String =
        """
        Baruch wants to get out of this obligation. Is this the best available
        excuse and plan for his situation? Assess the message and hallway script.
        Set approved to true if the plan is ready for Baruch to consider sending;
        otherwise explain what should improve. Select the appropriate tier.
        Judge the supplied plan and context; you have no tools or external actions.

        OBLIGATION: ${Scenario.EVENT_TITLE}
        ORGANIZER: ${Scenario.ORGANIZER}
        ATTENDEES: ${Scenario.ATTENDEES.joinToString()}
        RECENTLY USED FLAVORS: ${Scenario.BURNED.joinToString()}
        CONTEXT: ${Scenario.USER_CONTEXT}

        PROPOSED PLAN:
        $deployment
        """.trimIndent()

    /** The app-supplied prompt, before Codex adds its own CLI instructions. */
    internal fun codexPrompt(deployment: DeclineDeployment): String =
        "$CODEX_SYSTEM_PROMPT\n\n${codexRequest(deployment)}"

    fun codex(): CliAIAgent<DeclineDeployment, DeclineCritique> =
        TypedCodex.agent(
            serializer = serializer<DeclineCritique>(),
            systemPrompt = CODEX_SYSTEM_PROMPT,
            request = ::codexRequest,
        )
}

internal fun cliWorkspace(role: String): Path =
    Files.createTempDirectory("jclaw-$role-").also { it.toFile().deleteOnExit() }

/**
 * A null API-key argument does not remove an inherited API key. Strip API billing
 * and alternate-provider settings while retaining each CLI's subscription login.
 * env receives separate argv entries; no prompt or credential is sent through a shell.
 */
internal object SubscriptionCliTransport : ProcessCliTransport() {
    private val removedVariables = listOf(
        "OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL",
        "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL",
        "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
    )

    override fun buildCommand(
        command: List<String>,
        workspace: String,
        env: Map<String, String>,
    ): List<String> = listOf("/usr/bin/env") +
        removedVariables.flatMap { listOf("-u", it) } + command
}
