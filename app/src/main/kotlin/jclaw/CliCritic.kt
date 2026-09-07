package jclaw

import ai.koog.agents.cli.CliAIAgent
import ai.koog.agents.cli.CliAgentStructuredResponse
import ai.koog.agents.cli.transport.CliTransport
import jclaw.domain.DeclineCritique
import jclaw.domain.DeclineDeployment
import jclaw.domain.Scenario
import java.nio.file.Files

/**
 * The critic, running on somebody else's model entirely.
 *
 * Koog 1.1.1 added `CliAIAgent` - an agent that shells out to another vendor's
 * CLI (claude, codex) and plugs into a graph via `.asNode()`. No API key: it
 * uses whatever subscription that CLI is already logged into.
 *
 * Which makes the "don't let the model grade its own homework" argument
 * literal. Gemini drafts the excuse. Claude decides whether it would survive
 * contact with People Ops. Different company, different weights, different
 * incentives - and the handoff between them is a typed data class, so neither
 * one needs to know the other exists.
 */
private val NO_TOOLS = """
    {"permissions":{"deny":["Bash","Read","Edit","Write","WebFetch","WebSearch","Glob","Grep","Task","NotebookEdit"]}}
""".trimIndent()

object CliCritic {

    /**
     * A scratch directory the CLI agent is penned into.
     *
     * Coding agents explore. Benchmarking this talk, grok went and read
     * demo-spec.md off the working directory uninvited to get the answer right.
     * Impressive, and absolutely not what you want a pipeline stage doing.
     */
    private val pen: String = Files.createTempDirectory("jclaw-critic").toFile().apply {
        deleteOnExit()
    }.absolutePath

    fun claude(): CliAIAgent<DeclineDeployment, CliAgentStructuredResponse<DeclineCritique>> =
        CliAIAgent.claude(
            transport = CliTransport.default(),
            outputClass = DeclineCritique::class,
            // apiKey stays null on purpose: that is what makes it use the subscription.
            apiKey = null,
            name = "hostile-reviewer",
            workspace = pen,
            // The critic reviews text. It has no business holding the calendar,
            // mail and travel MCP servers this machine has configured - and
            // `workspace` only scopes the filesystem, so it would. Asked to help
            // with a meeting under BypassPermissions, an earlier build of round 1
            // read a real itinerary and created a real calendar event.
            //   --strict-mcp-config : only MCP from --mcp-config; we pass none
            //   --settings          : and none of the built-ins either
            // Not --tools/--disallowedTools: variadic, they swallow the prompt.
            additionalFlags = listOf("--strict-mcp-config", "--settings", NO_TOOLS),
            systemPrompt = "You are a hostile reviewer inside an approval pipeline. " +
                "You do not have opinions about whether the user should attend. You only " +
                "judge whether the plan survives scrutiny. Answer with the structured " +
                "result and nothing else.",
            generateRequest = { deployment: DeclineDeployment ->
                """
            Review this plan to get out of a mandatory corporate training session.

            Reject it (approved = false) if ANY of these hold:
              - the flavor is one already burned with this organizer: ${Scenario.BURNED.joinToString()}
              - the message does not actually match the flavor it claims
              - the staged calendar event does not cover the session time
              - a TOUCHY organizer would check and catch it

            Prefer excuses that are literally true - those survive any amount of checking.
            Context you should weigh: ${Scenario.USER_CONTEXT}

            Set tier to the honest PlausibilityTier. Be specific in feedback about what to fix.

                THE PLAN:
                $deployment
                """.trimIndent()
            },
        )
}
