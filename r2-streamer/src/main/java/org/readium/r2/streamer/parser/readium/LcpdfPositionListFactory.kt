/*
 * Module: r2-shared-kotlin
 * Developers: Mickaël Menu
 *
 * Copyright (c) 2020. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.streamer.parser.readium

import android.content.Context
import org.readium.r2.shared.PdfSupport
import org.readium.r2.shared.format.MediaType
import org.readium.r2.shared.pdf.PdfDocument
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.streamer.container.Container
import org.readium.r2.streamer.fetcher.DrmDecoder
import org.readium.r2.streamer.parser.pdf.PdfiumDocument
import timber.log.Timber

/**
 * Creates the [positions] for an LCP protected PDF [Publication] from its [readingOrder] and
 * [container].
 */
@OptIn(PdfSupport::class)
internal class LcpdfPositionListFactory(
    private val context: Context,
    private val container: Container,
    private val readingOrder: List<Link>
) : Publication.PositionListFactory {

    override fun create(): List<Locator> {
        // Calculates the page count of each resource from the reading order.
        val resources: List<Pair<Int, Link>> = readingOrder.map { link ->
            val pageCount = openPdfAt(link)?.pageCount ?: 0
            Pair(pageCount, link)
        }

        val totalPageCount = resources.sumBy { it.first }
        if (totalPageCount <= 0) {
            return emptyList()
        }

        var lastPositionOfPreviousResource = 0
        return resources.flatMap { (pageCount, link) ->
            val positions = createPositionsOf(link, pageCount = pageCount, totalPageCount = totalPageCount, startPosition = lastPositionOfPreviousResource)
            lastPositionOfPreviousResource += pageCount
            positions
        }
    }

    private fun createPositionsOf(link: Link, pageCount: Int, totalPageCount: Int, startPosition: Int): List<Locator> {
        if (pageCount <= 0 || totalPageCount <= 0) {
            return emptyList()
        }

        // FIXME: Use the [tableOfContents] to generate the titles
        return (1..pageCount).map { position ->
            val progression = (position - 1) / pageCount.toDouble()
            val totalProgression = (startPosition + position - 1) / totalPageCount.toDouble()
            Locator(
                href = link.href,
                type = link.type ?: MediaType.PDF.toString(),
                locations = Locator.Locations(
                    fragments = listOf("page=$position"),
                    progression = progression,
                    totalProgression = totalProgression,
                    position = startPosition + position
                )
            )
        }
    }

    private fun openPdfAt(link: Link): PdfDocument? =
        try {
            container.dataInputStream(link.href)
                .let { decoder.decoding(it, link, container.drm) }
                .let { PdfiumDocument.fromBytes(it.readBytes(), context) }
        } catch (e: Exception) {
            Timber.e(e)
            null
        }

    private val decoder = DrmDecoder()

}
