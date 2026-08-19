package dev.jenil.lbp2900

import dev.jenil.capt.PageGeometry
import dev.jenil.capt.PageOptions

/** How a PDF page is placed on the sheet. */
enum class ScaleMode(val label: String) {
    /** Scale to fill the printable area, preserving aspect ratio. */
    FIT("Fit to page"),

    /** Render at true size; anything past the printable area is clipped. */
    ACTUAL("Actual size"),
    ;

    override fun toString() = label
}

/** Paper the printer is loaded with, mapped to its fuser mode. */
enum class Media(val label: String, val code: Int) {
    PLAIN("Plain paper", 0x00),
    THICK("Thick paper", 0x01),
    THICK_H("Thick paper H", 0x03),
    ENVELOPE("Envelope", 0x06),
    ;

    override fun toString() = label
}

/**
 * Everything the user can change before pressing Print.
 *
 * Deliberately small: this printer has very few knobs that actually do
 * anything. Its PPD exposes no density control at all, so there is no
 * point offering one — a real CUPS job always sends zero.
 */
data class PrintSettings(
    val paperSize: String = "A4",
    val copies: Int = 1,
    val tonerSave: Boolean = false,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val media: Media = Media.PLAIN,
    /** Blank means every page. Otherwise "1-3,5,8-" style. */
    val pageRange: String = "",
) {
    val geometry: PageGeometry get() = PageGeometry.byName(paperSize)

    val pageOptions: PageOptions
        get() = PageOptions(
            mediaType = media.code,
            tonerSave = if (tonerSave) 1 else 0,
        )

    fun summary(): String = buildString {
        append(paperSize)
        append(", ").append(scaleMode.label.lowercase())
        if (copies > 1) append(", ").append(copies).append(" copies")
        if (tonerSave) append(", toner save")
        if (media != Media.PLAIN) append(", ").append(media.label.lowercase())
        if (pageRange.isNotBlank()) append(", pages ").append(pageRange)
    }
}
