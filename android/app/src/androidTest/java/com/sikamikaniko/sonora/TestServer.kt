package com.sikamikaniko.sonora

import androidx.test.platform.app.InstrumentationRegistry

/** Live tests use an explicit test deployment rather than a developer's server. */
internal fun testServerAddress(): String = requireNotNull(
    InstrumentationRegistry.getArguments().getString("workstationServerUrl")?.takeIf { it.isNotBlank() }
) { "Provide workstationServerUrl as an instrumentation argument" }
