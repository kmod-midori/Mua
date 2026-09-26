package moe.reimu.nekoassistant.ai

import moe.reimu.nekoassistant.ai.tool.Action

data class AgentResponse(
    val think: String,
    val action: Action
)
