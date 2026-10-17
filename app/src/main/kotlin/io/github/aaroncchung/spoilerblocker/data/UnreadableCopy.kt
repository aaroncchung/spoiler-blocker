package io.github.aaroncchung.spoilerblocker.data

import java.io.File

/**
 * Copies [file], which could not be read, to a new file beside it and returns
 * the copy. A repository calls this before it replaces an unreadable file with
 * an empty one, so that what the file held can still be recovered by hand.
 *
 * blockers.json is copied to blockers.json.unreadable or, if that name is
 * taken, to the first free name of blockers.json.unreadable.2, .3 and so on.
 * An earlier copy is never replaced. It may be the only copy left of what an
 * earlier file held.
 *
 * @throws java.io.IOException if the copy cannot be made. The caller must let
 *   it through, so that the file is not replaced without a copy.
 */
fun keepUnreadableCopy(file: File): File {
    val folder = file.parentFile
    var copy = File(folder, file.name + ".unreadable")
    var number = 2
    while (copy.exists()) {
        copy = File(folder, file.name + ".unreadable." + number)
        number++
    }
    return file.copyTo(copy, overwrite = false)
}
