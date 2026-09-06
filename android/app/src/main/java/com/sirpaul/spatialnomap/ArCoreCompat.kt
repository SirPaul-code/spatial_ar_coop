package com.sirpaul.spatialnomap

import com.google.ar.core.Config

/**
 * Explicit Java-accessor bridge for ARCore APIs whose synthetic Kotlin property
 * is not exposed consistently across ARCore/Kotlin plugin combinations.
 */
val Config.depthMode: Config.DepthMode
    get() = getDepthMode()
