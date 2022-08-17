package de.morhenn.ar_navigation.util

import com.google.android.gms.maps.model.LatLng

object GeoUtils {

    fun getPointByDistanceAndBearing(lat: Double, lon: Double, bearing: Double, distanceKm: Double): LatLng {
        val earthRadius = 6378.1

        val bearingR = Math.toRadians(bearing)

        val latR = Math.toRadians(lat)
        val lonR = Math.toRadians(lon)

        val distanceToRadius = distanceKm / earthRadius

        val newLatR = Math.asin(Math.sin(latR) * Math.cos(distanceToRadius) +
                Math.cos(latR) * Math.sin(distanceToRadius) * Math.cos(bearingR))
        val newLonR = lonR + Math.atan2(Math.sin(bearingR) * Math.sin(distanceToRadius) * Math.cos(latR),
            Math.cos(distanceToRadius) - Math.sin(latR) * Math.sin(newLatR))

        val latNew = Math.toDegrees(newLatR)
        val lonNew = Math.toDegrees(newLonR)

        return LatLng(latNew, lonNew)
    }

    fun distanceBetweenTwoCoordinates(latlng1: LatLng, latlng2: LatLng): Double {
        val earthRadius = 6378.1
        val lat1 = Math.toRadians(latlng1.latitude)
        val lon1 = Math.toRadians(latlng1.longitude)
        val lat2 = Math.toRadians(latlng2.latitude)
        val lon2 = Math.toRadians(latlng2.longitude)
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c * 1000
    }
}