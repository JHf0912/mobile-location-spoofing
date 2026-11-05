package com.snake.mobilespoofing.ui.input

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint

/** Result of parsing a coordinate typed or pasted by the user. */
sealed interface CoordinateParseResult {
    data class Ok(val point: GeoPoint) : CoordinateParseResult

    data class Invalid(val reason: CoordinateParseError) : CoordinateParseResult
}

enum class CoordinateParseError { Empty, Malformed, OutOfRange, NotFinite }

/**
 * Parses the formats the UI advertises:
 *
 *  - `lat,lon` — datum [defaultDatum] because that is what the caller claims it is;
 *  - `geo:lat,lon` and `geo:lat,lon?z=...` — a geo URI pair, always WGS84;
 *  - `geo:base?q=lat,lon(label)` — the `q` pair wins, always WGS84. If `q` is a label/address
 *    rather than a pair, the base pair is used only when it is valid and not the 0,0 placeholder;
 *    `geo:0,0?q=Office` stays invalid.
 *
 * This is deliberately separate from [com.snake.mobilespoofing.ui.MainViewModel.onMapPointSelected]:
 * map taps are GCJ02 from AMap, while typed coordinates are the user's own datum. Reusing the map
 * entry point would shift every pasted WGS84 coordinate by hundreds of metres.
 */
fun parseCoordinateInput(
    raw: String,
    defaultDatum: CoordType = CoordType.WGS84,
): CoordinateParseResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return CoordinateParseResult.Invalid(CoordinateParseError.Empty)

    return if (trimmed.startsWith(GEO_PREFIX, ignoreCase = true)) {
        parseGeoUri(trimmed.substring(GEO_PREFIX.length))
    } else {
        parsePair(trimmed, defaultDatum)
    }
}

private const val GEO_PREFIX = "geo:"

private fun parseGeoUri(rest: String): CoordinateParseResult {
    if (rest.isBlank()) return CoordinateParseResult.Invalid(CoordinateParseError.Malformed)

    val queryIndex = rest.indexOf('?')
    val baseText = (if (queryIndex >= 0) rest.substring(0, queryIndex) else rest).trim()
    val query = if (queryIndex >= 0) rest.substring(queryIndex + 1) else ""

    val qParameter = query.split('&').firstOrNull { parameter ->
        parameter.substringBefore('=').trim().equals(Q_PARAMETER, ignoreCase = true)
    } ?: return parsePair(baseText, CoordType.WGS84)

    val qValue = decodePercentEncodedComma(
        qParameter.substringAfter('=', missingDelimiterValue = "").trim(),
    )
    val qPairText = stripParenthesizedLabel(qValue)
    if (qPairText.isEmpty()) return parseGeoQueryFallback(baseText)

    return when (val qResult = parsePair(qPairText, CoordType.WGS84)) {
        is CoordinateParseResult.Ok -> qResult

        is CoordinateParseResult.Invalid ->
            if (qResult.reason == CoordinateParseError.Malformed) {
                // q is an address/label rather than coordinates; a real base pair is the point.
                parseGeoQueryFallback(baseText)
            } else {
                // q looked like a coordinate pair but is out of range / non-finite: report that
                // instead of silently substituting the base (usually the 0,0 placeholder).
                qResult
            }
    }
}

/**
 * Used only when q is present but is not a coordinate pair: accept a valid, non-placeholder base
 * pair; otherwise reject. `geo:0,0?q=Office` must stay invalid rather than becoming (0,0).
 */
private fun parseGeoQueryFallback(baseText: String): CoordinateParseResult {
    if (baseText.isEmpty()) return CoordinateParseResult.Invalid(CoordinateParseError.Malformed)

    return when (val base = parsePair(baseText, CoordType.WGS84)) {
        is CoordinateParseResult.Ok ->
            if (base.point.latitude == 0.0 && base.point.longitude == 0.0) {
                CoordinateParseResult.Invalid(CoordinateParseError.Malformed)
            } else {
                base
            }

        is CoordinateParseResult.Invalid -> base
    }
}

/** Handles the common `%2C` comma encoding in pasted geo URIs; labels are not decoded or used. */
private fun decodePercentEncodedComma(value: String): String =
    value.replace("%2C", ",", ignoreCase = true)

private const val Q_PARAMETER = "q"

/** Turns `39.9,116.4(Office)` into `39.9,116.4`; leaves values without a trailing label alone. */
private fun stripParenthesizedLabel(value: String): String {
    val open = value.indexOf('(')
    if (open <= 0 || !value.endsWith(')')) return value
    return value.substring(0, open).trim()
}

private fun parsePair(text: String, datum: CoordType): CoordinateParseResult {
    val parts = text.split(',')
    if (parts.size != 2) return CoordinateParseResult.Invalid(CoordinateParseError.Malformed)

    val latitudeText = parts[0].trim()
    val longitudeText = parts[1].trim()
    if (latitudeText.isEmpty() || longitudeText.isEmpty()) {
        return CoordinateParseResult.Invalid(CoordinateParseError.Malformed)
    }

    // toDoubleOrNull also accepts "NaN" and "Infinity"; those are finite-checked below so the
    // user gets a precise reason instead of a generic format error.
    val latitude = latitudeText.toDoubleOrNull()
        ?: return CoordinateParseResult.Invalid(CoordinateParseError.Malformed)
    val longitude = longitudeText.toDoubleOrNull()
        ?: return CoordinateParseResult.Invalid(CoordinateParseError.Malformed)

    if (!latitude.isFinite() || !longitude.isFinite()) {
        return CoordinateParseResult.Invalid(CoordinateParseError.NotFinite)
    }
    if (latitude !in MIN_LATITUDE..MAX_LATITUDE || longitude !in MIN_LONGITUDE..MAX_LONGITUDE) {
        return CoordinateParseResult.Invalid(CoordinateParseError.OutOfRange)
    }

    return CoordinateParseResult.Ok(GeoPoint(latitude, longitude, datum))
}

private const val MIN_LATITUDE = -90.0
private const val MAX_LATITUDE = 90.0
private const val MIN_LONGITUDE = -180.0
private const val MAX_LONGITUDE = 180.0
