package de.morhenn.ar_navigation.util

import com.google.android.gms.maps.model.LatLng

object GeoUtils {

    fun getLatLngByDistanceAndBearing(lat: Double, lng: Double, bearing: Double, distanceKm: Double): LatLng {
        val earthRadius = 6378.1

        val bearingR = Math.toRadians(bearing)

        val latR = Math.toRadians(lat)
        val lngR = Math.toRadians(lng)

        val distanceToRadius = distanceKm / earthRadius

        val newLatR = Math.asin(Math.sin(latR) * Math.cos(distanceToRadius) +
                Math.cos(latR) * Math.sin(distanceToRadius) * Math.cos(bearingR))
        val newLonR = lngR + Math.atan2(Math.sin(bearingR) * Math.sin(distanceToRadius) * Math.cos(latR),
            Math.cos(distanceToRadius) - Math.sin(latR) * Math.sin(newLatR))

        val latNew = Math.toDegrees(newLatR)
        val lngNew = Math.toDegrees(newLonR)

        return LatLng(latNew, lngNew)
    }


    //calculate new Latlng with offsetX and offsetY
    //offsetX in meters towards startHeading
    //offsetY in meters towards startHeading + 90°
    fun getLatLngByLocalCoordinateOffset(startLat: Double, startLng: Double, startHeading: Double, offsetX: Float, offsetZ: Float): LatLng {
        val latLngOnlyX = getLatLngByDistanceAndBearing(startLat, startLng, (startHeading + 90.0) % 360, offsetX / 1000.0)

        return getLatLngByDistanceAndBearing(latLngOnlyX.latitude, latLngOnlyX.longitude, startHeading, -offsetZ / 1000.0)
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
