package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import kotlinx.coroutines.runBlocking


val anthropicApiKey: String = System.getenv("ANTHROPIC_API_KEY")
val promptExecutor = simpleAnthropicExecutor(anthropicApiKey)

val agentStrategy = strategy("Simple todoist MCP") {
    val nodeSendInput by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()
    val nodeSendToolResult by nodeLLMSendToolResult()

    edge(nodeStart forwardTo nodeSendInput)

    edge( (nodeSendInput forwardTo nodeFinish) transformed { it } onAssistantMessage { true } )

    edge( (nodeSendInput forwardTo nodeExecuteTool) onToolCall { true } )

    edge(nodeExecuteTool forwardTo nodeSendToolResult)

    edge( (nodeSendToolResult forwardTo nodeSendInput) transformed { it } onAssistantMessage { true } )
}

val agentConfig = AIAgentConfig(
    prompt = Prompt.build("todoist-mcp-agent") {
        system(
            """
                You are an assistant for using the todoist app. You can add, update, delete, complete todos in the app 
                and you can give a list of open todos. Also there are dates, topics etc. for which you can filter. 
                """.trimIndent()
        )
    },
    model = AnthropicModels.Haiku_3_5,
    maxAgentIterations = 10
)

fun main() {
    val process = startTodoistMcpServer()

    try {
        runBlocking {
            try {
                val toolRegistry = createToolRegistryWithTodoistMcp(process)
                val agent = AIAgent(
                    promptExecutor = promptExecutor,
                    strategy = agentStrategy,
                    agentConfig = agentConfig,
                    toolRegistry = toolRegistry,
                    installFeatures = {
                        install(EventHandler) {
                            onBeforeAgentStarted = { strategy: AIAgentStrategy, agent: AIAgent ->
                                println("Starting strategy: ${strategy.name}")
                            }
                            onAgentFinished = { strategyName: String, result: String? ->
                                println("Result: $result")
                            }
                        }
                    }
                )

                runAgent(agent)

            } catch (e: Exception) {
                println("Error connecting to Todoist MCP server: ${e.message}")
                e.printStackTrace()
            }
        }
    } finally {
        println("Disconnecting from Todoist MCP server.")
        process.destroy()
    }
}

private suspend fun runAgent(agent: AIAgent) {
    println("What would you like me to do?")
    val userInput = readlnOrNull() ?: ""
    agent.run(userInput)
}

private suspend fun createToolRegistryWithTodoistMcp(process: Process): ToolRegistry {
    println("Connecting to Todoist MCP server...")
    val toolRegistry = McpToolRegistryProvider.fromTransport(
        transport = McpToolRegistryProvider.defaultStdioTransport(process)
    )
    println("Successfully connected to Todoist MCP server")
    return toolRegistry
}

private fun startTodoistMcpServer(): Process {
    val process = ProcessBuilder("npx", "@abhiz123/todoist-mcp-server").start()
    Thread.sleep(2000)
    return process
}