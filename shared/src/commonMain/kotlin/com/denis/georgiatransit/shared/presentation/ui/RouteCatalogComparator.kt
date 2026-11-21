package com.denis.georgiatransit.shared.presentation.ui

import com.denis.georgiatransit.shared.domain.model.TransitRoute

/**
 * Presentation-only ordering retained from DEN-66. The raw catalogue stays in BFF order for
 * selection membership and the shared resolver; Kotlin's stable sort preserves that order for
 * public-field ties and never exposes opaque IDs as a fallback.
 */
internal object RouteCatalogComparator : Comparator<TransitRoute> {
    override fun compare(a: TransitRoute, b: TransitRoute): Int =
        naturalCompare(a.shortName, b.shortName)
            .takeUnless { it == 0 }
            ?: a.shortName.compareTo(b.shortName, ignoreCase = true)
                .takeUnless { it == 0 }
            ?: a.shortName.compareTo(b.shortName)
                .takeUnless { it == 0 }
            ?: a.longName.en.compareTo(b.longName.en)
                .takeUnless { it == 0 }
            ?: a.longName.ru.compareTo(b.longName.ru)
                .takeUnless { it == 0 }
            ?: a.longName.ka.compareTo(b.longName.ka)
                .takeUnless { it == 0 }
            ?: a.mode.name.compareTo(b.mode.name)
                .takeUnless { it == 0 }
            ?: 0
}

private fun naturalCompare(first: String, second: String): Int {
    var firstIndex = 0
    var secondIndex = 0
    while (firstIndex < first.length && secondIndex < second.length) {
        val firstChar = first[firstIndex]
        val secondChar = second[secondIndex]
        if (firstChar.isDigit() && secondChar.isDigit()) {
            val firstEnd = firstIndex.consumeDigits(first)
            val secondEnd = secondIndex.consumeDigits(second)
            val firstSignificant = firstIndex.skipLeadingZeroes(first, firstEnd)
            val secondSignificant = secondIndex.skipLeadingZeroes(second, secondEnd)
            val firstDigits = firstEnd - firstSignificant
            val secondDigits = secondEnd - secondSignificant
            if (firstDigits != secondDigits) return firstDigits.compareTo(secondDigits)
            for (index in 0 until firstDigits) {
                val comparison = first[firstSignificant + index].compareTo(second[secondSignificant + index])
                if (comparison != 0) return comparison
            }
            firstIndex = firstEnd
            secondIndex = secondEnd
        } else {
            val comparison = firstChar.lowercaseChar().compareTo(secondChar.lowercaseChar())
            if (comparison != 0) return comparison
            firstIndex += 1
            secondIndex += 1
        }
    }
    return (first.length - firstIndex).compareTo(second.length - secondIndex)
}

private fun Int.consumeDigits(value: String): Int {
    var index = this
    while (index < value.length && value[index].isDigit()) index += 1
    return index
}

private fun Int.skipLeadingZeroes(value: String, endExclusive: Int): Int {
    var index = this
    while (index < endExclusive - 1 && value[index] == '0') index += 1
    return index
}
