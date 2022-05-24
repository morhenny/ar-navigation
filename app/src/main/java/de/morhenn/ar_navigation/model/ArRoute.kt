package de.morhenn.ar_navigation.model

import kotlinx.serialization.Serializable

@Serializable
data class ArRoute(var cloudAnchorId: String, var pointsList: List<ArPoint>)

