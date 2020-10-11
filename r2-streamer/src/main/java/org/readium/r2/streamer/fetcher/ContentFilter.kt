/*
 * Module: r2-streamer-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.streamer.fetcher

import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.Injectable
import org.readium.r2.shared.ReadiumCSSName
import org.readium.r2.shared.publication.ContentLayout
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.epub.layout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.streamer.container.Container
import org.readium.r2.streamer.server.Resources
import java.io.File
import java.io.InputStream

internal interface ContentFilters {
    fun apply(input: InputStream, publication: Publication, container: Container, path: String): InputStream {
        return input
    }

    fun apply(input: ByteArray, publication: Publication, container: Container, path: String): ByteArray {
        return input
    }
}

internal class ContentFiltersEpub(private val userPropertiesPath: String?, private var customResources: Resources?) : ContentFilters {

    override fun apply(input: InputStream, publication: Publication, container: Container, path: String): InputStream {
        publication.linkWithHref(path)?.let { resourceLink ->

            var decodedInputStream = DrmDecoder().decoding(input, resourceLink, container.drm)
            decodedInputStream = FontDecoder().decoding(decodedInputStream, publication, path)
            if (resourceLink.mediaType?.isHtml == true) {
                decodedInputStream = if (publication.metadata.presentation.layout == EpubLayout.REFLOWABLE && resourceLink.properties.layout == null
                        || resourceLink.properties.layout == EpubLayout.REFLOWABLE) {
                    injectReflowableHtml(decodedInputStream, publication)
                } else {
                    injectFixedLayoutHtml(decodedInputStream)
                }
            }

            return decodedInputStream
        } ?: run {
            return input
        }

    }

    override fun apply(input: ByteArray, publication: Publication, container: Container, path: String): ByteArray {
        publication.linkWithHref(path)?.let { resourceLink ->
            val inputStream = input.inputStream()
            var decodedInputStream = DrmDecoder().decoding(inputStream, resourceLink, container.drm)
            decodedInputStream = FontDecoder().decoding(decodedInputStream, publication, path)
            if (resourceLink.mediaType?.isHtml == true
                    && publication.baseUrl != null) {
                decodedInputStream =
                        if (publication.metadata.presentation.layout == EpubLayout.REFLOWABLE && (resourceLink.properties.layout == null
                                        || resourceLink.properties.layout == EpubLayout.REFLOWABLE)) {
                            injectReflowableHtml(decodedInputStream, publication)
                        } else {
                            injectFixedLayoutHtml(decodedInputStream)
                        }
            }
            return decodedInputStream.readBytes()
        } ?: run {
            return input
        }
    }

    private fun injectReflowableHtml(stream: InputStream, publication: Publication): InputStream {
        val data = stream.readBytes()
        var resourceHtml = String(data).trim()
        // Inject links to css and js files
        var beginHeadIndex = resourceHtml.indexOf("<head>", 0, false) + 6
        var endHeadIndex = resourceHtml.indexOf("</head>", 0, false)
        if (endHeadIndex == -1)
            return stream

        val contentLayout = publication.contentLayout

        val endIncludes = mutableListOf<String>()
        val beginIncludes = mutableListOf<String>()
        beginIncludes.add("<meta name=\"viewport\" content=\"width=device-width, height=device-height, initial-scale=1.0, maximum-scale=1.0, user-scalable=0\" />")

        beginIncludes.add(getHtmlLink("/assets/readium-css/${contentLayout.readiumCSSPath}ReadiumCSS-before.css"))
        endIncludes.add(getHtmlLink("/assets/readium-css/${contentLayout.readiumCSSPath}ReadiumCSS-after.css"))
        endIncludes.add(getHtmlScript("/assets/scripts/touchHandling.js"))
        endIncludes.add(getHtmlScript("/assets/scripts/utils.js"))
        endIncludes.add(getHtmlScript("/assets/scripts/crypto-sha256.js"))
        endIncludes.add(getHtmlScript("/assets/scripts/highlight.js"))

        customResources?.let {
            // Inject all custom resourses
            for ((key, value) in it.resources) {
                if (value is Pair<*, *>) {
                    val res = value as Pair<String, String>
                    if (Injectable(res.second) == Injectable.Script) {
                        endIncludes.add(getHtmlScript("/${Injectable.Script.rawValue}/$key"))
                    } else if (Injectable(res.second) == Injectable.Style) {
                        endIncludes.add(getHtmlLink("/${Injectable.Style.rawValue}/$key"))
                    }
                }
            }
        }

        for (element in beginIncludes) {
            resourceHtml = StringBuilder(resourceHtml).insert(beginHeadIndex, element).toString()
            beginHeadIndex += element.length
            endHeadIndex += element.length
        }
        for (element in endIncludes) {
            resourceHtml = StringBuilder(resourceHtml).insert(endHeadIndex, element).toString()
            endHeadIndex += element.length
        }
        resourceHtml = StringBuilder(resourceHtml).insert(endHeadIndex, getHtmlFont(fontFamily = "OpenDyslexic", href = "/assets/fonts/OpenDyslexic-Regular.otf")).toString()
        resourceHtml = StringBuilder(resourceHtml).insert(endHeadIndex, "<style>@import url('https://fonts.googleapis.com/css?family=PT+Serif|Roboto|Source+Sans+Pro|Vollkorn');</style>\n").toString()

        // Inject userProperties
        getProperties(publication.userSettingsUIPreset)?.let { propertyPair ->
            val html = Regex("""<html.*>""").find(resourceHtml, 0)
            html?.let {
                val match = Regex("""(style=("([^"]*)"[ >]))|(style='([^']*)'[ >])""").find(html.value, 0)
                if (match != null) {
                    val beginStyle = match.range.first + 7
                    var newHtml = html.value
                    newHtml = StringBuilder(newHtml).insert(beginStyle, "${buildStringProperties(propertyPair)} ").toString()
                    resourceHtml = StringBuilder(resourceHtml).replace(Regex("""<html.*>"""), newHtml)
                } else {
                    val beginHtmlIndex = resourceHtml.indexOf("<html", 0, false) + 5
                    resourceHtml = StringBuilder(resourceHtml).insert(beginHtmlIndex, " style=\"${buildStringProperties(propertyPair)}\"").toString()
                }
            } ?:run {
                val beginHtmlIndex = resourceHtml.indexOf("<html", 0, false) + 5
                resourceHtml = StringBuilder(resourceHtml).insert(beginHtmlIndex, " style=\"${buildStringProperties(propertyPair)}\"").toString()
            }
        }

        resourceHtml = applyDirectionAttribute(resourceHtml, publication)

        return resourceHtml.toByteArray().inputStream()
    }

    private fun applyDirectionAttribute(resourceHtml: String, publication: Publication): String {
        var resourceHtml1 = resourceHtml
        fun addRTLDir(tagName: String, html: String): String {
            return Regex("""<$tagName.*>""").find(html, 0)?.let { result ->
                Regex("""dir=""").find(result.value, 0)?.let {
                    html
                } ?: run {
                    val beginHtmlIndex = html.indexOf("<$tagName", 0, false) + 5
                    StringBuilder(html).insert(beginHtmlIndex, " dir=\"rtl\"").toString()
                }
            } ?: run {
                html
            }
        }

        if (publication.cssStyle == "rtl") {
            resourceHtml1 = addRTLDir("html", resourceHtml1)
            resourceHtml1 = addRTLDir("body", resourceHtml1)
        }

        return resourceHtml1
    }

    private fun injectFixedLayoutHtml(stream: InputStream): InputStream {
        val data = stream.readBytes()
        var resourceHtml = String(data) //UTF-8
        val endHeadIndex = resourceHtml.indexOf("</head>", 0, false)
        if (endHeadIndex == -1)
            return stream
        val includes = mutableListOf<String>()
        includes.add(getHtmlScript("/"+ Injectable.Script.rawValue +"/touchHandling.js"))
        includes.add(getHtmlScript("/"+ Injectable.Script.rawValue +"/utils.js"))
        for (element in includes) {
            resourceHtml = StringBuilder(resourceHtml).insert(endHeadIndex, element).toString()
        }
        return resourceHtml.toByteArray().inputStream()
    }

    private fun getHtmlFont(fontFamily: String, href: String): String {
        val prefix = "<style type=\"text/css\"> @font-face{font-family: \"$fontFamily\"; src:url(\""
        val suffix = "\") format('truetype');}</style>\n"
        return prefix + href + suffix
    }

    private fun getHtmlLink(resourceName: String): String {
        val prefix = "<link rel=\"stylesheet\" type=\"text/css\" href=\""
        val suffix = "\"/>\n"
        return prefix + resourceName + suffix
    }

    private fun getHtmlScript(resourceName: String): String {
        val prefix = "<script type=\"text/javascript\" src=\""
        val suffix = "\"></script>\n"

        return prefix + resourceName + suffix
    }

    private fun getProperties(preset: MutableMap<ReadiumCSSName, Boolean>): MutableMap<String, String>? {

        // userProperties is a JSON string containing the css userProperties
        var userPropertiesString: String? = null
        userPropertiesPath?.let {
            userPropertiesString = String()
            val file = File(it)
            if (file.isFile && file.canRead()) {
                for (i in file.readLines()) {
                    userPropertiesString += i
                }
            }
        }

        return userPropertiesString?.let {
            // Parsing of the String into a JSONArray of JSONObject with each "name" and "value" of the css properties
            // Making that JSONArray a MutableMap<String, String> to make easier the access of data
            return@let try {
                val propertiesArray = JSONArray(userPropertiesString)
                val properties: MutableMap<String, String> = mutableMapOf()
                for (i in 0 until propertiesArray.length()) {
                    val value = JSONObject(propertiesArray.getString(i))
                    var isInPreset = false

                    for (property in preset) {
                        if (property.key.ref == value.getString("name")) {
                            isInPreset = true
                            val presetPair = Pair(property.key, preset[property.key])
                            val presetValue = applyPreset(presetPair)
                            properties[presetValue.getString("name")] = presetValue.getString("value")
                        }
                    }

                    if (!isInPreset) {
                        properties[value.getString("name")] = value.getString("value")
                    }

                }
                properties
            } catch (e: Exception) {
                null
            }
        }
    }


    private fun applyPreset(preset: Pair<ReadiumCSSName, Boolean?>): JSONObject {
        val readiumCSSProperty = JSONObject()

        readiumCSSProperty.put("name", preset.first.ref)

        when(preset.first) {
            ReadiumCSSName.hyphens -> {
                readiumCSSProperty.put("value", "")
            }
            ReadiumCSSName.fontOverride -> {
                readiumCSSProperty.put("value", "readium-font-off")
            }
            ReadiumCSSName.appearance -> {
                readiumCSSProperty.put("value", "readium-default-on")
            }
            ReadiumCSSName.publisherDefault -> {
                readiumCSSProperty.put("value", "")
            }
            ReadiumCSSName.columnCount -> {
                readiumCSSProperty.put("value", "auto")
            }
            ReadiumCSSName.pageMargins -> {
                readiumCSSProperty.put("value", "0.5")
            }
            ReadiumCSSName.lineHeight -> {
                readiumCSSProperty.put("value", "1.0")
            }
            ReadiumCSSName.ligatures -> {
                readiumCSSProperty.put("value", "")
            }
            ReadiumCSSName.fontFamily -> {
                readiumCSSProperty.put("value", "Original")
            }
            ReadiumCSSName.fontSize -> {
                readiumCSSProperty.put("value", "100%")
            }
            ReadiumCSSName.wordSpacing -> {
                readiumCSSProperty.put("value", "0.0rem")
            }
            ReadiumCSSName.letterSpacing -> {
                readiumCSSProperty.put("value", "0.0em")
            }
            ReadiumCSSName.textAlignment -> {
                readiumCSSProperty.put("value", "justify")
            }
            ReadiumCSSName.paraIndent -> {
                readiumCSSProperty.put("value", "")
            }
            ReadiumCSSName.scroll -> {
                if (preset.second!!) {
                    readiumCSSProperty.put("value", "readium-scroll-on")
                } else {
                    readiumCSSProperty.put("value", "readium-scroll-off")
                }
            }
        }

        return readiumCSSProperty
    }

    private fun buildStringProperties(list: MutableMap<String, String>): String {
        var string = ""
        for (property in list) {
            string = string + " " + property.key + ": " + property.value + ";"
        }
        return string
    }

    private val ContentLayout.readiumCSSPath: String get() = when(this)  {
        ContentLayout.LTR -> ""
        ContentLayout.RTL -> "rtl/"
        ContentLayout.CJK_VERTICAL -> "cjk-vertical/"
        ContentLayout.CJK_HORIZONTAL -> "cjk-horizontal/"
    }

}

internal class ContentFiltersCbz : ContentFilters

/** Content filter for LCP protected packages (except EPUB). */
internal class ContentFiltersLcp : ContentFilters {

    override fun apply(input: InputStream, publication: Publication, container: Container, path: String): InputStream {
        val resourceLink = publication.linkWithHref(path)
            ?: return input

        return DrmDecoder().decoding(input, resourceLink, container.drm)
    }

    override fun apply(input: ByteArray, publication: Publication, container: Container, path: String): ByteArray {
        val resourceLink = publication.linkWithHref(path)
            ?: return input

        val inputStream = input.inputStream()
        return DrmDecoder().decoding(inputStream, resourceLink, container.drm).readBytes()
    }

}
