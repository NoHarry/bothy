package cc.noharry.bothy.kernel.core

import cc.noharry.bothy.kernel.api.reliability.OpenConfusion
import cc.noharry.bothy.kernel.api.reliability.RuntimeBlocker
import cc.noharry.bothy.kernel.api.reliability.RuntimeHandoff

data class WaitingUserInputState(
    val handoff: RuntimeHandoff,
    val openConfusion: OpenConfusion,
    val blocker: RuntimeBlocker,
)
