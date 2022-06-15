package de.morhenn.ar_navigation.persistance

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Place(
    @PrimaryKey
    var id: String,
    var name: String,
    var lat: Double,
    var lng: Double,
    var alt: Double,
    var heading: Double,
    var description: String,
    var author: String,
    var ardata: String
)