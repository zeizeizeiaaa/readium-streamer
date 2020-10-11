/*
 * Module: r2-shared-kotlin
 * Developers: Mickaël Menu
 *
 * Copyright (c) 2020. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.streamer.parser

import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

class PerResourcePositionListFactoryTest {

    @Test
    fun `Create from an empty {readingOrder}`() {
        val factory = PerResourcePositionListFactory(readingOrder = emptyList())

        assertEquals(0, factory.create().size)
    }

    @Test
    fun `Create from a {readingOrder} with one resource`() {
        val factory = PerResourcePositionListFactory(readingOrder = listOf(
            Link(href = "res", type = "image/png")
        ))

        assertEquals(
            listOf(Locator(
                href = "res",
                type = "image/png",
                locations = Locator.Locations(
                    position = 1,
                    totalProgression = 0.0
                )
            )),
            factory.create()
        )
    }

    @Test
    fun `Create from a {readingOrder} with a few resources`() {
        val factory = PerResourcePositionListFactory(readingOrder = listOf(
            Link(href = "res"),
            Link(href = "chap1", type = "image/png"),
            Link(href = "chap2", type = "image/png", title = "Chapter 2")
        ))

        assertEquals(
            listOf(
                Locator(
                    href = "res",
                    type = "",
                    locations = Locator.Locations(
                        position = 1,
                        totalProgression = 0.0
                    )
                ),
                Locator(
                    href = "chap1",
                    type = "image/png",
                    locations = Locator.Locations(
                        position = 2,
                        totalProgression = 1.0/3.0
                    )
                ),
                Locator(
                    href = "chap2",
                    type = "image/png",
                    title = "Chapter 2",
                    locations = Locator.Locations(
                        position = 3,
                        totalProgression = 2.0/3.0
                    )
                )
            ),
            factory.create()
        )
    }

    @Test
    fun `{type} fallbacks on the given media type`() {
        val factory = PerResourcePositionListFactory(
            readingOrder = listOf(
                Link(href = "res")
            ),
            fallbackMediaType = "image/*"
        )

        assertEquals(
            listOf(Locator(
                href = "res",
                type = "image/*",
                locations = Locator.Locations(
                    position = 1,
                    totalProgression = 0.0
                )
            )),
            factory.create()
        )
    }

}
