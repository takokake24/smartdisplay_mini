package app.smartdisplay.jihou

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/** 日本標準時。このアプリの範囲では夏時間がない。 */
private val JapanOffset: ZoneOffset = ZoneOffset.ofHours(9)

/**
 * 太陽黄経が [longitudeDegrees] になる瞬間を含む日付。
 * [near] はその日の前後数日。立春は 315 度、夏至は 90 度、秋分は 180 度、冬至は 270 度。
 */
internal fun solarTermDate(longitudeDegrees: Double, near: LocalDate): LocalDate {
    return jdeToJapanDate(solarTermJde(longitudeDegrees, near))
}

/** 秋分の瞬間を含む旧暦の月の十五日（中秋の名月）。 */
internal fun harvestMoonDate(year: Int): LocalDate {
    val equinox = solarTermJde(180.0, LocalDate.of(year, 9, 23))
    return jdeToJapanDate(newMoonAtOrBefore(equinox)).plusDays(14)
}

private fun solarTermJde(longitudeDegrees: Double, near: LocalDate): Double {
    val center = japanNoonToJde(near)
    var lo = center - 4.0
    var hi = center + 4.0
    var guard = 0
    while (guard < 8 && longitudeOffset(apparentSolarLongitude(lo), longitudeDegrees) > 0.0) {
        lo -= 2.0
        guard += 1
    }
    guard = 0
    while (guard < 8 && longitudeOffset(apparentSolarLongitude(hi), longitudeDegrees) < 0.0) {
        hi += 2.0
        guard += 1
    }
    repeat(48) {
        val mid = (lo + hi) / 2.0
        if (longitudeOffset(apparentSolarLongitude(mid), longitudeDegrees) < 0.0) {
            lo = mid
        } else {
            hi = mid
        }
    }
    return (lo + hi) / 2.0
}

/** 太陽の視黄経（度）。略算の VSOP87 に章動と光行差を足す。 */
private fun apparentSolarLongitude(jde: Double): Double {
    val centuries = (jde - 2451545.0) / 36525.0
    val tau = centuries / 10.0
    val earthLongitude = toDegrees(sumSeries(EarthLongitude, tau))
    val earthLatitude = toDegrees(sumSeries(EarthLatitude, tau))
    val radius = sumSeries(EarthRadius, tau)
    var longitude = earthLongitude + 180.0
    val latitude = -earthLatitude
    val lambdaPrime = longitude - 1.397 * centuries - 0.00031 * centuries * centuries
    val frame = -0.09033 + 0.03916 * (cosDeg(lambdaPrime) + sinDeg(lambdaPrime)) * tanDeg(latitude)
    longitude += frame / 3600.0
    val aberration = -20.4898 / radius
    longitude += nutationLongitude(centuries) / 3600.0 + aberration / 3600.0
    return floorMod(longitude, 360.0)
}

private fun nutationLongitude(centuries: Double): Double {
    val omega = 125.04452 - 1934.136261 * centuries + 0.0020708 * centuries * centuries +
        centuries * centuries * centuries / 450000.0
    val sun = 280.4665 + 36000.7698 * centuries
    val moon = 218.3165 + 481267.8813 * centuries
    return -17.20 * sinDeg(omega) -
        1.32 * sinDeg(2.0 * sun) -
        0.23 * sinDeg(2.0 * moon) +
        0.21 * sinDeg(2.0 * omega)
}

/** 朔の瞬間（地球時のユリウス日）。 */
private fun newMoonJde(k: Double): Double {
    val t = k / 1236.85
    val t2 = t * t
    val t3 = t2 * t
    val t4 = t3 * t
    val mean = 2451550.09766 + 29.530588861 * k + 0.00015437 * t2 -
        0.000000150 * t3 + 0.00000000073 * t4
    val e = 1.0 - 0.002516 * t - 0.0000074 * t2
    val e2 = e * e
    val anomaly = 2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3
    val moon = 201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4
    val f = 160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4
    val node = 124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3
    var correction = -0.40720 * sinDeg(moon) +
        0.17241 * e * sinDeg(anomaly) +
        0.01608 * sinDeg(2.0 * moon) +
        0.01039 * sinDeg(2.0 * f) +
        0.00739 * e * sinDeg(moon - anomaly) -
        0.00514 * e * sinDeg(moon + anomaly) +
        0.00208 * e2 * sinDeg(2.0 * anomaly) -
        0.00111 * sinDeg(moon - 2.0 * f) -
        0.00057 * sinDeg(moon + 2.0 * f) +
        0.00056 * e * sinDeg(2.0 * moon + anomaly) -
        0.00042 * sinDeg(3.0 * moon) +
        0.00042 * e * sinDeg(anomaly + 2.0 * f) +
        0.00038 * e * sinDeg(anomaly - 2.0 * f) -
        0.00024 * e * sinDeg(2.0 * moon - anomaly) -
        0.00017 * sinDeg(node)
    val a1 = 299.77 + 0.107408 * k - 0.009173 * t2
    val a2 = 251.88 + 0.016321 * k
    val a3 = 251.83 + 26.651886 * k
    val a4 = 349.42 + 36.412478 * k
    val a5 = 84.66 + 18.206239 * k
    val a6 = 141.74 + 53.303771 * k
    val a7 = 207.14 + 2.453732 * k
    val a8 = 154.84 + 7.306860 * k
    val a9 = 34.52 + 27.261239 * k
    val a10 = 207.19 + 0.121824 * k
    val a11 = 291.34 + 1.844379 * k
    val a12 = 161.72 + 24.198154 * k
    val a13 = 239.56 + 25.513099 * k
    val a14 = 331.55 + 3.592518 * k
    correction += 0.000325 * sinDeg(a1) +
        0.000165 * sinDeg(a2) +
        0.000164 * sinDeg(a3) +
        0.000126 * sinDeg(a4) +
        0.000110 * sinDeg(a5) +
        0.000062 * sinDeg(a6) +
        0.000060 * sinDeg(a7) +
        0.000056 * sinDeg(a8) +
        0.000047 * sinDeg(a9) +
        0.000042 * sinDeg(a10) +
        0.000040 * sinDeg(a11) +
        0.000037 * sinDeg(a12) +
        0.000035 * sinDeg(a13) +
        0.000023 * sinDeg(a14)
    return mean + correction
}

private fun newMoonAtOrBefore(jde: Double): Double {
    var k = round((jde - 2451550.09766) / 29.530588861)
    repeat(6) {
        val start = newMoonJde(k)
        val next = newMoonJde(k + 1.0)
        when {
            start > jde -> k -= 1.0
            next <= jde -> k += 1.0
            else -> return start
        }
    }
    return newMoonJde(k)
}

private fun japanNoonToJde(date: LocalDate): Double {
    val instant = date.atTime(12, 0).toInstant(JapanOffset)
    val jdUtc = instant.epochSecond.toDouble() / 86400.0 +
        instant.nano.toDouble() / 1_000_000_000.0 / 86400.0 + 2440587.5
    return jdUtc + deltaTSeconds(jdUtc) / 86400.0
}

private fun jdeToJapanDate(jde: Double): LocalDate {
    val jdUtc = jde - deltaTSeconds(jde) / 86400.0
    val unix = (jdUtc - 2440587.5) * 86400.0
    val seconds = floor(unix).toLong()
    val nanos = ((unix - seconds.toDouble()) * 1_000_000_000.0).toLong()
    return Instant.ofEpochSecond(seconds, nanos).atOffset(JapanOffset).toLocalDate()
}

/** TT と UTC の差（秒）。日付が深夜 0 時の前後でも日を取り違えないために使う。 */
private fun deltaTSeconds(jde: Double): Double {
    val year = 2000.0 + (jde - 2451545.0) / 365.2425
    val t = year - 2000.0
    return when {
        year >= 1986.0 && year < 2005.0 ->
            63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t +
                0.000651814 * t * t * t * t + 0.00002373599 * t * t * t * t * t
        year >= 2005.0 && year < 2050.0 ->
            62.92 + 0.32217 * t + 0.005589 * t * t
        else -> {
            val u = (year - 1820.0) / 100.0
            -20.0 + 32.0 * u * u
        }
    }
}

private fun sumSeries(series: Array<Array<DoubleArray>>, tau: Double): Double {
    var total = 0.0
    var power = 1.0
    for (terms in series) {
        var sum = 0.0
        for (term in terms) {
            sum += term[0] * cos(term[1] + term[2] * tau)
        }
        total += sum * power
        power *= tau
    }
    return total / 1.0e8
}

private fun longitudeOffset(longitude: Double, target: Double): Double {
    return floorMod(longitude - target + 180.0, 360.0) - 180.0
}

private fun floorMod(value: Double, modulus: Double): Double {
    val remainder = value % modulus
    return if (remainder < 0.0) remainder + modulus else remainder
}

private fun toDegrees(radians: Double): Double = radians * 180.0 / PI

private fun sinDeg(degrees: Double): Double = sin(degrees * PI / 180.0)

private fun cosDeg(degrees: Double): Double = cos(degrees * PI / 180.0)

private fun tanDeg(degrees: Double): Double = tan(degrees * PI / 180.0)

private val EarthLongitude: Array<Array<DoubleArray>> = arrayOf(
    arrayOf(
        doubleArrayOf(175347046.0, 0.0, 0.0), doubleArrayOf(3341656.0, 4.6692568, 6283.07585),
        doubleArrayOf(34894.0, 4.6261, 12566.1517), doubleArrayOf(3497.0, 2.7441, 5753.3849),
        doubleArrayOf(3418.0, 2.8289, 3.5231), doubleArrayOf(3136.0, 3.6277, 77713.7715),
        doubleArrayOf(2676.0, 4.4181, 7860.4194), doubleArrayOf(2343.0, 6.1352, 3930.2097),
        doubleArrayOf(1324.0, 0.7425, 11506.7698), doubleArrayOf(1273.0, 2.0371, 529.6910),
        doubleArrayOf(1199.0, 1.1096, 1577.3435), doubleArrayOf(990.0, 5.233, 5884.927),
        doubleArrayOf(902.0, 2.045, 26.298), doubleArrayOf(857.0, 3.508, 398.149),
        doubleArrayOf(780.0, 1.179, 5223.694), doubleArrayOf(753.0, 2.533, 5507.553),
        doubleArrayOf(505.0, 4.583, 18849.228), doubleArrayOf(492.0, 4.205, 775.523),
        doubleArrayOf(357.0, 2.920, 0.067), doubleArrayOf(317.0, 5.849, 11790.629),
        doubleArrayOf(284.0, 1.899, 796.298), doubleArrayOf(271.0, 0.315, 10977.079),
        doubleArrayOf(243.0, 0.345, 5486.778), doubleArrayOf(206.0, 4.806, 2544.314),
        doubleArrayOf(205.0, 1.869, 5573.143), doubleArrayOf(202.0, 2.458, 6069.777),
        doubleArrayOf(156.0, 0.833, 213.299), doubleArrayOf(132.0, 3.411, 2942.463),
        doubleArrayOf(126.0, 1.083, 20.775), doubleArrayOf(115.0, 0.645, 0.980),
        doubleArrayOf(103.0, 0.636, 4694.003), doubleArrayOf(102.0, 0.976, 15720.839),
        doubleArrayOf(102.0, 4.267, 7.114), doubleArrayOf(99.0, 6.21, 2146.17),
        doubleArrayOf(98.0, 0.68, 155.42), doubleArrayOf(86.0, 5.98, 161000.69),
        doubleArrayOf(85.0, 1.30, 6275.96), doubleArrayOf(85.0, 3.67, 71430.70),
        doubleArrayOf(80.0, 1.81, 17260.15), doubleArrayOf(79.0, 3.04, 12036.46),
        doubleArrayOf(75.0, 1.76, 5088.63), doubleArrayOf(74.0, 3.50, 3154.69),
        doubleArrayOf(74.0, 4.68, 801.82), doubleArrayOf(70.0, 0.83, 9437.76),
        doubleArrayOf(62.0, 3.98, 8827.39), doubleArrayOf(61.0, 1.82, 7084.90),
        doubleArrayOf(57.0, 2.78, 6286.60), doubleArrayOf(56.0, 4.39, 14143.50),
        doubleArrayOf(56.0, 3.47, 6279.55), doubleArrayOf(52.0, 0.19, 12139.55),
        doubleArrayOf(52.0, 1.33, 1748.02), doubleArrayOf(51.0, 0.28, 5856.48),
        doubleArrayOf(49.0, 0.49, 1194.45), doubleArrayOf(41.0, 5.37, 8429.24),
        doubleArrayOf(41.0, 2.40, 19651.05), doubleArrayOf(39.0, 6.17, 10447.39),
        doubleArrayOf(37.0, 6.04, 10213.29), doubleArrayOf(37.0, 2.57, 1059.38),
        doubleArrayOf(36.0, 1.71, 2352.87), doubleArrayOf(36.0, 1.78, 6812.77),
        doubleArrayOf(33.0, 0.59, 17789.85), doubleArrayOf(30.0, 0.44, 83996.85),
        doubleArrayOf(30.0, 2.74, 1349.87), doubleArrayOf(25.0, 3.16, 4690.48),
    ),
    arrayOf(
        doubleArrayOf(628331966747.0, 0.0, 0.0), doubleArrayOf(206059.0, 2.678235, 6283.07585),
        doubleArrayOf(4303.0, 2.6351, 12566.1517), doubleArrayOf(425.0, 1.590, 3.523),
        doubleArrayOf(119.0, 5.796, 26.298), doubleArrayOf(109.0, 2.966, 1577.344),
        doubleArrayOf(93.0, 2.59, 18849.23), doubleArrayOf(72.0, 1.14, 529.69),
        doubleArrayOf(68.0, 1.87, 398.15), doubleArrayOf(67.0, 4.41, 5507.55),
        doubleArrayOf(59.0, 2.89, 5223.69), doubleArrayOf(56.0, 2.17, 155.42),
        doubleArrayOf(45.0, 0.40, 796.30), doubleArrayOf(36.0, 0.47, 775.52),
        doubleArrayOf(29.0, 2.65, 7.11), doubleArrayOf(21.0, 5.34, 0.98),
        doubleArrayOf(19.0, 1.85, 5486.78), doubleArrayOf(19.0, 4.97, 213.30),
        doubleArrayOf(17.0, 2.99, 6275.96), doubleArrayOf(16.0, 0.03, 2544.31),
        doubleArrayOf(16.0, 1.43, 2146.17), doubleArrayOf(15.0, 1.21, 10977.08),
        doubleArrayOf(12.0, 2.83, 1748.02), doubleArrayOf(12.0, 3.26, 5088.63),
        doubleArrayOf(12.0, 5.27, 1194.45), doubleArrayOf(12.0, 2.08, 4694.00),
        doubleArrayOf(11.0, 0.77, 553.57), doubleArrayOf(10.0, 1.30, 6286.60),
        doubleArrayOf(10.0, 4.24, 1349.87), doubleArrayOf(9.0, 2.70, 242.73),
        doubleArrayOf(9.0, 5.64, 951.72), doubleArrayOf(8.0, 5.30, 2352.87),
        doubleArrayOf(6.0, 2.65, 9437.76), doubleArrayOf(6.0, 4.67, 4690.48),
    ),
    arrayOf(
        doubleArrayOf(52919.0, 0.0, 0.0), doubleArrayOf(8720.0, 1.0721, 6283.0758),
        doubleArrayOf(309.0, 0.867, 12566.152), doubleArrayOf(27.0, 0.05, 3.52),
        doubleArrayOf(16.0, 5.19, 26.30), doubleArrayOf(16.0, 3.68, 155.42),
        doubleArrayOf(10.0, 0.76, 18849.23), doubleArrayOf(9.0, 2.06, 77713.77),
        doubleArrayOf(7.0, 0.83, 775.52), doubleArrayOf(5.0, 4.66, 1577.34),
        doubleArrayOf(4.0, 1.03, 7.11), doubleArrayOf(4.0, 3.44, 5573.14),
        doubleArrayOf(3.0, 5.14, 796.30), doubleArrayOf(3.0, 6.05, 5507.55),
        doubleArrayOf(3.0, 1.19, 242.73), doubleArrayOf(3.0, 6.12, 529.69),
        doubleArrayOf(3.0, 0.31, 398.15), doubleArrayOf(3.0, 2.28, 553.57),
        doubleArrayOf(2.0, 4.38, 5223.69), doubleArrayOf(2.0, 3.75, 0.98),
    ),
    arrayOf(
        doubleArrayOf(289.0, 5.844, 6283.076), doubleArrayOf(35.0, 0.0, 0.0),
        doubleArrayOf(17.0, 5.49, 12566.15), doubleArrayOf(3.0, 5.20, 155.42),
        doubleArrayOf(1.0, 4.72, 3.52), doubleArrayOf(1.0, 5.30, 18849.23),
        doubleArrayOf(1.0, 5.97, 242.73),
    ),
    arrayOf(
        doubleArrayOf(114.0, 3.142, 0.0), doubleArrayOf(8.0, 4.13, 6283.08),
        doubleArrayOf(1.0, 3.84, 12566.15),
    ),
    arrayOf(
        doubleArrayOf(1.0, 3.14, 0.0),
    ),
)

private val EarthLatitude: Array<Array<DoubleArray>> = arrayOf(
    arrayOf(
        doubleArrayOf(280.0, 3.199, 84334.662), doubleArrayOf(102.0, 5.422, 5507.553),
        doubleArrayOf(80.0, 3.88, 5223.69), doubleArrayOf(44.0, 3.70, 2352.87),
        doubleArrayOf(32.0, 4.00, 1577.34),
    ),
    arrayOf(
        doubleArrayOf(9.0, 3.90, 5507.55), doubleArrayOf(6.0, 1.73, 5223.69),
    ),
)

private val EarthRadius: Array<Array<DoubleArray>> = arrayOf(
    arrayOf(
        doubleArrayOf(100013989.0, 0.0, 0.0), doubleArrayOf(1670700.0, 3.0984635, 6283.07585),
        doubleArrayOf(13956.0, 3.05525, 12566.1517), doubleArrayOf(3084.0, 5.1985, 77713.7715),
        doubleArrayOf(1628.0, 1.1739, 5753.3849), doubleArrayOf(1576.0, 2.8469, 7860.4194),
        doubleArrayOf(925.0, 5.453, 11506.770), doubleArrayOf(542.0, 4.564, 3930.210),
        doubleArrayOf(472.0, 3.661, 5884.927), doubleArrayOf(346.0, 0.964, 5507.553),
        doubleArrayOf(329.0, 5.900, 5223.694), doubleArrayOf(307.0, 0.299, 5573.143),
        doubleArrayOf(243.0, 4.273, 11790.629), doubleArrayOf(212.0, 5.847, 1577.344),
        doubleArrayOf(186.0, 5.022, 10977.079), doubleArrayOf(175.0, 3.012, 18849.228),
        doubleArrayOf(110.0, 5.055, 5486.778), doubleArrayOf(98.0, 0.89, 6069.78),
        doubleArrayOf(86.0, 5.69, 15720.84), doubleArrayOf(86.0, 1.27, 161000.69),
        doubleArrayOf(65.0, 0.27, 17260.15), doubleArrayOf(63.0, 0.92, 529.69),
        doubleArrayOf(57.0, 2.01, 83996.85), doubleArrayOf(56.0, 5.24, 71430.70),
        doubleArrayOf(49.0, 3.25, 2544.31), doubleArrayOf(47.0, 2.58, 775.52),
        doubleArrayOf(45.0, 5.54, 9437.76), doubleArrayOf(43.0, 6.01, 6275.96),
        doubleArrayOf(39.0, 5.36, 4694.00), doubleArrayOf(38.0, 2.39, 8827.39),
        doubleArrayOf(37.0, 0.83, 19651.05), doubleArrayOf(37.0, 4.90, 12139.55),
        doubleArrayOf(36.0, 1.67, 12036.46), doubleArrayOf(35.0, 1.84, 2942.46),
        doubleArrayOf(33.0, 0.24, 7084.90), doubleArrayOf(32.0, 0.18, 5088.63),
        doubleArrayOf(32.0, 1.78, 398.15), doubleArrayOf(28.0, 1.21, 6286.60),
        doubleArrayOf(28.0, 1.90, 6279.55), doubleArrayOf(26.0, 4.59, 10447.39),
    ),
    arrayOf(
        doubleArrayOf(103019.0, 1.10749, 6283.07585), doubleArrayOf(1721.0, 1.0644, 12566.1517),
        doubleArrayOf(702.0, 3.142, 0.0), doubleArrayOf(32.0, 1.02, 18849.23),
        doubleArrayOf(31.0, 2.84, 5507.55), doubleArrayOf(25.0, 1.32, 5223.69),
        doubleArrayOf(18.0, 1.42, 1577.34), doubleArrayOf(10.0, 5.91, 10977.08),
        doubleArrayOf(9.0, 1.42, 6275.96), doubleArrayOf(9.0, 0.27, 5486.78),
    ),
    arrayOf(
        doubleArrayOf(4359.0, 5.7846, 6283.0758), doubleArrayOf(124.0, 5.579, 12566.152),
        doubleArrayOf(12.0, 3.14, 0.0), doubleArrayOf(9.0, 3.63, 77713.77),
        doubleArrayOf(6.0, 1.87, 5573.14), doubleArrayOf(3.0, 5.47, 18849.23),
    ),
    arrayOf(
        doubleArrayOf(145.0, 4.273, 6283.076), doubleArrayOf(7.0, 3.92, 12566.15),
    ),
    arrayOf(
        doubleArrayOf(4.0, 2.56, 6283.08),
    ),
)
