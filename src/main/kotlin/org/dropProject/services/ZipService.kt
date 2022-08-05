/*-
 * ========================LICENSE_START=================================
 * DropProject
 * %%
 * Copyright (C) 2019 Pedro Alves
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.dropProject.services

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import org.dropProject.repository.*
import org.dropProject.storage.StorageException
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path

/**
 * Utility to create ZIP files based on folder contents.
 */
@Service
class ZipService {

    /**
     * Creates a ZIP File with the contents of [projectFolder].
     *
     * @param zipFilename is a String with the desired name for the ZIP file
     * @param projectFolder is a File containing the directory that shall be zipped
     *
     * @return a [ZipFile]
     */
    fun createZipFromFolder(zipFilename: String, projectFolder: File): ZipFile {
        val zFile = File.createTempFile(zipFilename, ".zip")
        if (zFile.exists()) {
            zFile.delete();
        }
        val zipFile = ZipFile(zFile)
        val zipParameters = ZipParameters()
        zipParameters.isIncludeRootFolder = false
        zipParameters.compressionLevel = CompressionLevel.ULTRA
        zipFile.addFolder(projectFolder, zipParameters)
        return zipFile
    }

    /**
     * Decompresses a ZIP file.
     *
     * @param file is a Path representing the .ZIP file to decompress
     * @param originalFilename is a String
     *
     * @return a File containing a directory with the unzipped files
     */
    fun unzip(file: Path, originalFilename: String?): File {
        val destinationFileFile = file.toFile()
        val destinationFolder = File(destinationFileFile.parent, destinationFileFile.nameWithoutExtension)
        try {
            val zipFile = ZipFile(destinationFileFile)
            zipFile.extractAll(destinationFolder.absolutePath)
        } catch (e: ZipException) {
            throw StorageException("Failed to unzip ${originalFilename}", e)
        }
        return destinationFolder
    }

}
