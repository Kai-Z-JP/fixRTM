/// Copyright (c) 2020 anatawa12 and other contributors
/// This file is/was part of fixRTM, released under GNU LGPL v3 with few exceptions
/// See LICENSE at https://github.com/fixrtm/fixRTM for more details

package com.anatawa12.fixRtm.io

import com.anatawa12.fixRtm.Loggers
import com.anatawa12.fixRtm.MS932
import com.anatawa12.fixRtm.directoryDigestBaseStream
import com.anatawa12.fixRtm.minecraftDir
import com.anatawa12.fixRtm.utils.DigestUtils
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.FMLLaunchHandler
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.ZipFile

object FIXFileLoader {
    val allModelPacks: Set<FIXModelPack>
    private val packs: Map<String, Set<FIXModelPack>>

    private val logger = Loggers.getLogger("FIXFileLoader")

    init {
        val packs = HashMap<String, MutableSet<FIXModelPack>>()

        for (file in getFiles()) {
            val pack = loadModelPack(file) ?: continue

            for (domain in pack.domains) {
                packs.computeIfAbsent(domain) { HashSet() }.add(pack)
            }
        }

        this.packs = packs

        allModelPacks = packs.flatMapTo(mutableSetOf()) { it.value }
        logger.trace("FIXFileLoader loads model packs:")
        for (pack in allModelPacks) {
            logger.trace("${pack.file.name}: ${pack.domains}")
        }
    }

    fun getFiles(): List<File> {
        return getModsOrJars().flatMap { it.walk().filter(File::isFile) }
    }

    fun getModsOrJars(): List<File> {
        val files = mutableListOf<File>()
        files += minecraftDir.resolve("mods")
        files += minecraftDir.resolve("jar-mods-cache/v1/mods")
        if (FMLLaunchHandler.isDeobfuscatedEnvironment()) {
            val loader = FIXFileLoader::class.java.classLoader
            fun zipUrlToFile(loader: ClassLoader, relative: String): File {
                val url = loader.getResource(relative)
                    ?: error("resource $relative not found")
                return when (url.protocol) {
                    "zip", "jar" -> File(URI(url.path.substringBefore('!')))
                    "file" -> File(URI(url.toString().dropLast(relative.length)))
                    else -> error("unsupported protocol: ${url.protocol}")
                }
            }
            // add fixRTM, rtm and
            files += zipUrlToFile(loader, FIXFileLoader::class.java.name.replace('.', '/') + ".class")
            files += zipUrlToFile(loader, "assets/rtm/lang/ja_JP.lang")
            files += zipUrlToFile(loader, "assets/ngtlib/lang/ja_JP.lang")
        }
        return files
    }

    fun getResource(location: ResourceLocation): FIXResource {
        for (fixModelPack in packs[location.namespace].orEmpty()) {
            fixModelPack.getFile(location)?.let { return it }
        }
        throw FileNotFoundException("$location")
    }

    fun getInputStream(location: ResourceLocation): InputStream = getResource(location).inputStream

    private fun loadModelPack(file: File): FIXModelPack? {
        try {
            try {
                if (file.isFile) {
                    if (file.extension != "jar" && file.extension != "zip") return null
                    return ZipModelPack(file)
                } else {
                    return DirectoryModelPack(file)
                }
            } catch (e: IllegalArgumentException) {
                if (file.isFile) {
                    return ZipModelPack(file, MS932)
                } else {
                    throw e
                }
            }
        } catch (e: Throwable) {
            logger.error("trying to construct model pack: ${file.name}", e)
            return null
        }
    }

    private class ZipModelPack(override val file: File, charset: Charset = Charsets.UTF_8) : FIXModelPack {
        private val zipFile = ZipFile(file, charset)

        override val sha1Hash: String = DigestUtils.sha1Hex(file.inputStream().buffered())

        override val domains: Set<String>
        private val ignoreCaseMap: Map<String, String>

        init {
            val domains = mutableSetOf<String>()
            val ignoreCaseMap = mutableMapOf<String, String>()
            for (entry in zipFile.entries()) {
                val parts = entry.name.split("/")
                if (parts[0] == "assets" && parts.size >= 2 && parts[1].isNotEmpty())
                    domains.add(parts[1])
                ignoreCaseMap[entry.name.lowercase()] = entry.name
            }
            this.domains = domains
            this.ignoreCaseMap = ignoreCaseMap
        }

        override fun getFile(location: ResourceLocation): FIXResource? {
            var path = "assets/${location.namespace}/${location.path}"
            path = ignoreCaseMap[path] ?: path
            val file = zipFile.getEntry(path) ?: return null
            return FIXResource(this, zipFile.getInputStream(file))
        }
    }

    private class DirectoryModelPack(override val file: File) : FIXModelPack {
        override val sha1Hash: String = DigestUtils.sha1Hex(file.directoryDigestBaseStream())

        override val domains: Set<String>

        init {
            val domains = mutableSetOf<String>()
            file.resolve("assets").listFiles()?.forEach { assetDir ->
                if (assetDir.isDirectory)
                    domains.add(assetDir.name)
            }
            this.domains = domains
        }

        override fun getFile(location: ResourceLocation): FIXResource? {
            val path = "assets/${location.namespace}/${location.path}"
            try {
                return FIXResource(this, file.resolve(path).inputStream())
            } catch (e: FileNotFoundException) {
                return null
            }
        }
    }

    fun load() {}
}
