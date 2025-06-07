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
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolResult
import ai.koog.agents.local.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import kotlinx.serialization.Serializable

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.

/// Use the Anthropic executor with an API key from an environment variable
val promptExecutor = simpleAnthropicExecutor(System.getenv("ANTHROPIC_API_KEY"))

// Create a simple strategy
val agentStrategy = strategy("Simple calculator") {
    // Define nodes for the strategy
    val nodeSendInput by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()
    val nodeSendToolResult by nodeLLMSendToolResult()

    // Define edges between nodes
    // Start -> Send input
    edge(nodeStart forwardTo nodeSendInput)

    // Send input -> Finish
    edge(
        (nodeSendInput forwardTo nodeFinish)
                transformed { it }
                onAssistantMessage { true }
    )

    // Send input -> Execute tool
    edge(
        (nodeSendInput forwardTo nodeExecuteTool)
                onToolCall { true }
    )

    // Execute tool -> Send the tool result
    edge(nodeExecuteTool forwardTo nodeSendToolResult)

    // Send the tool result -> finish
    edge(
        (nodeSendToolResult forwardTo nodeFinish)
                transformed { it }
                onAssistantMessage { true }
    )
}

// Configure the agent
val agentConfig = AIAgentConfig(
    prompt = Prompt.build("simple-calculator") {
        system(
            """
                You are a simple calculator assistant.
                You can add two numbers together using the calculator tool.
                When the user provides input, extract the numbers they want to add.
                The input might be in various formats like "add 5 and 7", "5 + 7", or just "5 7".
                Extract the two numbers and use the calculator tool to add them.
                Always respond with a clear, friendly message showing the calculation and result.
                """.trimIndent()
        )
    },
    model = AnthropicModels.Haiku_3_5,
    maxAgentIterations = 10
)

// Implement a simple calculator tool that can add two numbers
object CalculatorTool : Tool<CalculatorTool.Args, ToolResult>() {

    @Serializable
    data class Args(
        val num1: Int,
        val num2: Int
    ) : Tool.Args

    @Serializable
    data class Result(
        val sum: Int
    ) : ToolResult {
        override fun toStringDefault(): String {
            return "The sum is: $sum"
        }
    }

    override val argsSerializer = Args.serializer()

    override val descriptor = ToolDescriptor(
        name = "calculator",
        description = "Add two numbers together",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "num1",
                description = "First number to add",
                type = ToolParameterType.Integer
            ),
            ToolParameterDescriptor(
                name = "num2",
                description = "Second number to add",
                type = ToolParameterType.Integer
            )
        )
    )

    override suspend fun execute(args: Args): Result {
        // Perform a simple addition operation
        val sum = args.num1 + args.num2
        return Result(sum)
    }
}

// Create the tool to the tool registry
val toolRegistry = ToolRegistry {
    tool(CalculatorTool)
}

// Create the agent
val agent = AIAgent(
    promptExecutor = promptExecutor,
    strategy = agentStrategy,
    agentConfig = agentConfig,
    toolRegistry = toolRegistry,
    installFeatures = {
        // install the EventHandler feature
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

suspend fun main() {
    println("Enter two numbers to add (e.g., 'add 5 and 7' or '5 + 7'):")

    val userInput = readlnOrNull() ?: ""
    agent.run(userInput)
}