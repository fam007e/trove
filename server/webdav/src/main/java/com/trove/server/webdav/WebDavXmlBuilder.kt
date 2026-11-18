package com.trove.server.webdav

import com.trove.core.fs.FileItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A lightweight utility to build WebDAV XML responses (RFC 4918).
 */
class WebDavXmlBuilder {
    private val isoDateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val httpDateFormatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun buildMultiStatus(items: List<FileItem>, rootPath: String): String {
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n")
            append("<D:multistatus xmlns:D=\"DAV:\">\n")
            
            for (item in items) {
                append(renderResponse(item, rootPath))
            }
            
            append("</D:multistatus>")
        }
    }

    fun renderResponse(item: FileItem, rootPath: String): String {
        // Compute the href relative to the root
        val relativePath = item.path.removePrefix(rootPath).let { 
            if (it.startsWith("/")) it else "/$it" 
        }
        val href = if (item.isDirectory && !relativePath.endsWith("/")) "$relativePath/" else relativePath

        return buildString {
            append("  <D:response>\n")
            append("    <D:href>$href</D:href>\n")
            append("    <D:propstat>\n")
            append("      <D:prop>\n")
            
            if (item.isDirectory) {
                append("        <D:resourcetype><D:collection/></D:resourcetype>\n")
            } else {
                append("        <D:resourcetype/>\n")
                append("        <D:getcontentlength>${item.size}</D:getcontentlength>\n")
            }
            
            append("        <D:getlastmodified>${httpDateFormatter.format(Date(item.lastModified))}</D:getlastmodified>\n")
            append("        <D:creationdate>${isoDateFormatter.format(Date(item.lastModified))}</D:creationdate>\n")
            append("        <D:displayname>${item.name}</D:displayname>\n")
            append("        <D:getcontenttype>${item.mimeType}</D:getcontenttype>\n")
            
            append("      </D:prop>\n")
            append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
            append("    </D:propstat>\n")
            append("  </D:response>\n")
        }
    }
}
