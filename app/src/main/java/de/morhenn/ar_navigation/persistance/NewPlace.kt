package de.morhenn.ar_navigation.persistance

data class NewPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val heading: Double,
    val description: String,
    val author: String,
    val ardata: String
)