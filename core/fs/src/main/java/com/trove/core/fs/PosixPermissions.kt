package com.trove.core.fs

data class PosixPermissions(
    val ownerRead: Boolean,
    val ownerWrite: Boolean,
    val ownerExecute: Boolean,
    val groupRead: Boolean,
    val groupWrite: Boolean,
    val groupExecute: Boolean,
    val otherRead: Boolean,
    val otherWrite: Boolean,
    val otherExecute: Boolean
) {
    override fun toString(): String {
        return buildString {
            append(if (ownerRead) 'r' else '-')
            append(if (ownerWrite) 'w' else '-')
            append(if (ownerExecute) 'x' else '-')
            append(if (groupRead) 'r' else '-')
            append(if (groupWrite) 'w' else '-')
            append(if (groupExecute) 'x' else '-')
            append(if (otherRead) 'r' else '-')
            append(if (otherWrite) 'w' else '-')
            append(if (otherExecute) 'x' else '-')
        }
    }

    companion object {
        val DEFAULT = PosixPermissions(
            ownerRead = true, ownerWrite = true, ownerExecute = false,
            groupRead = true, groupWrite = false, groupExecute = false,
            otherRead = true, otherWrite = false, otherExecute = false
        )
        
        val DIRECTORY_DEFAULT = PosixPermissions(
            ownerRead = true, ownerWrite = true, ownerExecute = true,
            groupRead = true, groupWrite = false, groupExecute = true,
            otherRead = true, otherWrite = false, otherExecute = true
        )
    }
}
