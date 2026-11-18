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

            val segments = normalized.split('/')
            var current = root

            for (i in 0 until segments.size - 1) {
                val seg = segments[i]
                if (seg.isEmpty()) continue
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

            val leaf = ArchiveEntryNode(
                name = leafName,
                entryPath = normalized,
                isDirectory = isDirectory,
                size = size,
                lastModified = lastModified
            )
            current.addChild(leaf)
            return leaf
        }
    }
}
