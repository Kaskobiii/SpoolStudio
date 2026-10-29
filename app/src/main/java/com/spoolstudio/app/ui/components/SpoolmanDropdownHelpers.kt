package com.spoolstudio.app.ui.components

import com.spoolstudio.app.domain.models.FilamentSpool
import com.spoolstudio.app.domain.models.canonicalFilamentVariant
import com.spoolstudio.app.utils.suggestColorName
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

fun spoolmanDropdownLabel(filament: FilamentSpool): String {
    val name = filament.spoolmanName?.trim()?.takeIf { it.isNotBlank() }
        ?: suggestColorName(filament.colorHex)
    val variant = canonicalFilamentVariant(filament.materialModifier, filament.variant)
        .takeUnless { it.equals("Basic", ignoreCase = true) }.orEmpty()
    val material = listOf(filament.material.trim(), variant).filter { it.isNotBlank() }.joinToString(" ")
    val materialWords = spoolLabelWords(material)
    val nameWords = spoolLabelWords(name)
    val alreadyNamed = materialWords.isEmpty() ||
        nameWords.windowed(materialWords.size).any { it == materialWords }
    return if (alreadyNamed) name
    else listOf(name, material).filter { it.isNotBlank() }.joinToString(" ")
}

private fun spoolLabelWords(value: String): List<String> =
    Regex("[\\p{L}\\p{N}]+")
        .findAll(value.replace("+", " Plus ").lowercase(Locale.ROOT))
        .map { it.value }.toList()

fun spoolmanDropdownDetails(filament: FilamentSpool): String {
    val id = filament.id?.let { "ID #$it" } ?: "No ID"
    val grams = filament.remainingWeight?.takeIf { it.isFinite() }?.let {
        BigDecimal(it.toString()).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
    return "$id · ${grams?.let { "$it g remaining" } ?: "Weight unknown"}"
}

fun spoolmanDropdownListLabel(filament: FilamentSpool): String =
    "${spoolmanDropdownLabel(filament)} · ${spoolmanDropdownDetails(filament)}"

fun filterSpoolmanDropdownFilaments(
    filaments: List<FilamentSpool>,
    searchQuery: String
): List<FilamentSpool> {
    val query = searchQuery.trim()
    if (query.isBlank()) return filaments

    return filaments.filter { filament ->
        listOf(
            filament.id?.toString().orEmpty(),
            filament.brand,
            filament.spoolmanName.orEmpty(),
            filament.material,
            filament.materialModifier,
            filament.displayName,
            filament.variant,
            filament.location.orEmpty(),
            filament.owner.orEmpty(),
            filament.lotNr.orEmpty(),
            filament.colorHex.orEmpty()
        ).any { value ->
            value.contains(query, ignoreCase = true)
        }
    }
}
