package com.trove.feature.archive

class ArchiveEntryNode(
    val name: String,
    val entryPath: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    var parent: ArchiveEntryNode? = null
) {
    private val _children = mutableMapOf<String, ArchiveEntryNode>()
    val children: Map<String, ArchiveEntryNode> get() = _children

    fun addChild(child: ArchiveEntryNode) {
        child.parent = this
        _children[child.name] = child
    }

    fun findChild(childName: String): ArchiveEntryNode? = _children[childName]

    companion object {
        fun createRoot(): ArchiveEntryNode {
            return ArchiveEntryNode(
                name = "",
                entryPath = "",
                isDirectory = true
            )
        }

        fun insertPath(
            root: ArchiveEntryNode,
            rawPath: String,
            isDirectory: Boolean,
            size: Long,
            lastModified: Long
        ): ArchiveEntryNode {
            val normalized = rawPath.trim().replace('\\', '/').trim('/')
            if (normalized.isEmpty()) return root

            val rawSegments = normalized.split('/')
            // Security: Zip Slip (CVE-2018-1002201) defense.
            // Strip any directory traversal ('..'), relative dots ('.'), or illegal path tokens.
            val segments = rawSegments.filter { it.isNotEmpty() && it != "." && it != ".." }
            if (segments.isEmpty()) return root

            var current = root

            for (i in 0 until segments.size - 1) {
                val seg = segments[i]
                var child = current.findChild(seg)
                if (child == null) {
                    val subPath = segments.take(i + 1).joinToString("/")
                    child = ArchiveEntryNode(
                        name = seg,
                        entryPath = subPath,
                        isDirectory = true,
                        lastModified = lastModified
                    )
                    current.addChild(child)
                }
                current = child
            }

            val leafName = segments.last()
            val existing = current.findChild(leafName)
            if (existing != null) {
                return existing
            }

            val safeNormalized = segments.joinToString("/")
            val leaf = ArchiveEntryNode(
                name = leafName,
                entryPath = safeNormalized,
                isDirectory = isDirectory,
                size = size,
                lastModified = lastModified
            )
            current.addChild(leaf)
            return leaf
        }
    }
}
