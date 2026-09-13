package io.github.mangi.eta.agent.skill

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object SkillMutationLock {
    private val processLock = Any()
    private val heldRoots = ThreadLocal<MutableSet<String>>()

    fun <T> withLock(
        skillsRoot: File,
        recoveryHandler: ((List<RecoveredSkillOperation>) -> Unit)? = null,
        block: () -> T,
    ): T {
        val canonicalRoot = skillsRoot.canonicalFile
        val key = canonicalRoot.absolutePath
        val held = heldRoots.get() ?: linkedSetOf<String>().also(heldRoots::set)
        if (key in held) return block()

        return synchronized(processLock) {
            val lockDirectory = prepareSkillInstallerWorkRoot(canonicalRoot)
            val lockPath = File(lockDirectory, "install.lock")
            if (
                Files.exists(lockPath.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(lockPath.toPath()) ||
                    !Files.isRegularFile(lockPath.toPath(), LinkOption.NOFOLLOW_LINKS))
            ) {
                throw IOException("Skill 安装锁文件不安全")
            }
            RandomAccessFile(lockPath, "rw").use { lockFile ->
                if (Files.isSymbolicLink(lockPath.toPath())) {
                    throw IOException("Skill 安装锁文件不安全")
                }
                lockFile.channel.use { channel ->
                    channel.lock().use {
                        held += key
                        try {
                            val recovered = recoverPendingSkillOperations(canonicalRoot)
                            if (recovered.isNotEmpty()) {
                                val handler = recoveryHandler
                                    ?: throw SkillRecoveryRequiredException(
                                        "Skill 文件已恢复，等待 registry 恢复"
                                    )
                                try {
                                    handler(recovered)
                                    completeRecoveredSkillOperations(canonicalRoot, recovered)
                                } catch (error: SkillRecoveryRequiredException) {
                                    throw error
                                } catch (error: Exception) {
                                    throw SkillRecoveryRequiredException(
                                        "Skill registry 自动恢复失败",
                                        error,
                                    )
                                }
                            }
                            block()
                        } finally {
                            held -= key
                            if (held.isEmpty()) heldRoots.remove()
                        }
                    }
                }
            }
        }
    }
}

internal fun prepareSkillInstallerWorkRoot(skillsRoot: File): File {
    val canonicalRoot = skillsRoot.canonicalFile
    val parent = requireNotNull(canonicalRoot.parentFile) { "Skills 目录必须有父目录" }
    if (!Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Skills 父目录不可用")
    }
    val workRoot = File(parent, ".eta-skill-installer")
    val path = workRoot.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        try {
            Files.createDirectory(path)
        } catch (error: java.nio.file.FileAlreadyExistsException) {

        }
    }
    if (
        Files.isSymbolicLink(path) ||
        !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
        workRoot.canonicalFile.parentFile != parent.canonicalFile ||
        workRoot.canonicalFile != workRoot.absoluteFile
    ) {
        throw IOException("Skill 安装工作目录不安全")
    }
    return workRoot
}

internal fun isRecoverableSkillDirectoryTree(skillsRoot: File, target: File): Boolean {
    val canonicalRoot = runCatching { skillsRoot.canonicalFile.toPath() }.getOrNull() ?: return false
    if (Files.isSymbolicLink(target.toPath())) return false
    val canonicalTarget = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return false
    if (!canonicalTarget.startsWith(canonicalRoot) || canonicalTarget == canonicalRoot) return false
    return isRegularDirectoryTreeWithoutLinks(target.toPath())
}

private fun isRegularDirectoryTreeWithoutLinks(path: Path): Boolean {
    if (Files.isSymbolicLink(path)) return false
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return true
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
    return runCatching {
        Files.newDirectoryStream(path).use { children ->
            children.all(::isRegularDirectoryTreeWithoutLinks)
        }
    }.getOrDefault(false)
}

internal fun moveSkillDirectoryAtomically(source: File, target: File) {
    target.parentFile?.let { parent ->
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("无法创建目标父目录")
    }
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath())
    }
}

internal fun deleteSkillPathWithoutFollowingLinks(skillsRoot: File, target: File): Boolean {
    val lexicalRoot = skillsRoot.absoluteFile.toPath().normalize()
    val lexicalTarget = target.absoluteFile.toPath().normalize()
    if (!lexicalTarget.startsWith(lexicalRoot) || lexicalTarget == lexicalRoot) return false
    if (!Files.exists(lexicalTarget, LinkOption.NOFOLLOW_LINKS)) return true
    if (!Files.isSymbolicLink(lexicalTarget)) {
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val canonicalTarget = target.canonicalFile.toPath()
        if (!canonicalTarget.startsWith(canonicalRoot) || canonicalTarget == canonicalRoot) return false
    }
    return runCatching {
        deletePathTreeWithoutFollowingLinks(lexicalTarget)
        true
    }.getOrDefault(false)
}

private fun deletePathTreeWithoutFollowingLinks(path: Path) {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        Files.newDirectoryStream(path).use { children ->
            children.forEach(::deletePathTreeWithoutFollowingLinks)
        }
    }
    Files.deleteIfExists(path)
}
