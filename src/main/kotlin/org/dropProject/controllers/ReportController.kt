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
package org.dropProject.controllers

import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.util.Zip4jConstants
import org.apache.commons.io.FileUtils
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.dropProject.MAVEN_MAX_EXECUTION_TIME
import org.dropProject.dao.*
import org.dropProject.data.AuthorDetails
import org.dropProject.data.StudentHistory
import org.dropProject.data.TestType
import org.dropProject.extensions.formatDefault
import org.dropProject.extensions.realName
import org.dropProject.forms.SubmissionMethod
import org.dropProject.repository.*
import org.dropProject.services.*
import org.dropProject.storage.StorageService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.ModelMap
import org.springframework.web.bind.annotation.*
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import java.io.File
import java.io.FileWriter
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Paths
import java.security.Principal
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * ReportController contains MVC controller functions to handle requests related with submission reports
 * (for example, build report, submissions list, etc.).
 */
@Controller
class ReportController(
        val authorRepository: AuthorRepository,
        val projectGroupRepository: ProjectGroupRepository,
        val assignmentRepository: AssignmentRepository,
        val assignmentACLRepository: AssignmentACLRepository,
        val assignmentTestMethodRepository: AssignmentTestMethodRepository,
        val submissionRepository: SubmissionRepository,
        val gitSubmissionRepository: GitSubmissionRepository,
        val submissionReportRepository: SubmissionReportRepository,
        val buildReportRepository: BuildReportRepository,
        val assignmentTeacherFiles: AssignmentTeacherFiles,
        val buildReportBuilder: BuildReportBuilder,
        val gitClient: GitClient,
        val submissionService: SubmissionService,
        val storageService: StorageService,
        val zipService: ZipService,
        val templateEngine: TemplateEngine,
        val assignmentService: AssignmentService,
        val i18n: MessageSource
) {

    @Value("\${mavenizedProjects.rootLocation}")
    val mavenizedProjectsRootLocation: String = ""

    @Value("\${storage.rootLocation}/upload")
    val uploadSubmissionsRootLocation: String = "submissions/upload"

    @Value("\${storage.rootLocation}/git")
    val gitSubmissionsRootLocation: String = "submissions/git"

    @Value("\${spring.web.locale}")
    val currentLocale : Locale = Locale.getDefault()

    val LOG = LoggerFactory.getLogger(this.javaClass.name)

    /**
     * Controller that handles requests for the list of signalled groups in an [Assignment].
     * The signalled groups are groups of students that are failing exactly the same tests.
     * @param assignmentId is a String identifying the relevant Assignment
     * @param model is a [ModelMap] that will be populated with information to use in a View
     * @param principal is a [Principal] representing the user making the request
     * @param request is an [HttpServletRequest]
     * @return A String with the name of the relevant View
     */
    @RequestMapping(value = ["/signalledSubmissions/{assignmentId}"], method = [(RequestMethod.GET)])
    fun getSignaledGroupsOrSubmissions(@PathVariable assignmentId: String, model: ModelMap,
    principal: Principal, request: HttpServletRequest): String {
        model["assignmentId"] = assignmentId

        assignmentService.getAllSubmissionsForAssignment(assignmentId, principal, model, request,
                includeTestDetails = true,
                mode = "signalledSubmissions")

        return "signalled-submissions"
    }

    /**
     * Controller that handles requests for an [Assignment]'s report (for example, list of submissions per student/group).
     * @param assignmentId is a String identifying the relevant Assignment
     * @param model is a [ModelMap] that will be populated with information to use in a View
     * @param principal is a [Principal] representing the user making the request
     * @param request is an [HttpServletRequest]
     * @return A String with the name of the relevant View
     */
    @RequestMapping(value = ["/report/{assignmentId}"], method = [(RequestMethod.GET)])
    fun getReport(@PathVariable assignmentId: String, model: ModelMap,
                  principal: Principal, request: HttpServletRequest): String {

        assignmentService.getAllSubmissionsForAssignment(assignmentId, principal, model, request, mode = "summary")

        return "report"
    }

    /**
     * Controller that handles requests for an [Assignment]'s Test Matrix. The Test Matrix is a matrix where each row
     * represents a student/group and each column represents an evaluation test. The intersection between lines and
     * columns will tell us if each group has passed each specific test.
     * @param assignmentId is a String identifying the relevant Assignment
     * @param model is a [ModelMap] that will be populated with information to use in a View
     * @param principal is a [Principal] representing the user making the request
     * @param request is an [HttpServletRequest]
     * @return A String with the name of the relevant View
     */
    @RequestMapping(value = ["/testMatrix/{assignmentId}"], method = [(RequestMethod.GET)])
    fun getTestMatrix(@PathVariable assignmentId: String, model: ModelMap,
                  principal: Principal, request: HttpServletRequest): String {
        model["assignmentId"] = assignmentId

        assignmentService.getAllSubmissionsForAssignment(assignmentId, principal, model, request,
                includeTestDetails = true,
                mode = "testMatrix")

        return "test-matrix"
    }

    /**
     * Controller that handles requests for a [Submission]'s "Build Report".
     *
     * @param submissionId is a Long identifying the relevant Submission
     * @param model is a [ModelMap] that will be populated with information to use in a View
     * @param principal is a [Principal] representing the user making the request
     * @param request is an [HttpServletRequest]
     *
     * @return is a String identifying the relevant View
     */
    @RequestMapping(value = ["/buildReport/{submissionId}"], method = [(RequestMethod.GET)])
    fun getSubmissionReport(@PathVariable submissionId: Long, model: ModelMap, principal: Principal,
                            request: HttpServletRequest): String {

        val submission = submissionRepository.findById(submissionId).orElse(null)

        if (submission != null) {

            // check that principal belongs to the group that made this submission
            if (!request.isUserInRole("TEACHER")) {
                val groupElements = submission.group.authors
                if (groupElements.filter { it -> it.userId == principal.realName() }.isEmpty()) {
                    throw org.springframework.security.access.AccessDeniedException("${principal.realName()} is not allowed to view this report")
                }
            }

            if (submission.getStatus() == SubmissionStatus.DELETED) {
                throw AccessDeniedException("This submission was deleted")
            }

            model["numSubmissions"] = submissionRepository.countBySubmitterUserIdAndAssignmentId(principal.realName(), submission.assignmentId)

            val assignment = assignmentRepository.findById(submission.assignmentId).orElse(null)

            model["assignment"] = assignment

            model["submission"] = submission
            submission.overdue = assignment.overdue(submission)
            submission.gitSubmissionId?.let {
                gitSubmissionId ->
                    val gitSubmission = gitSubmissionRepository.getById(gitSubmissionId)
                    model["gitSubmission"] = gitSubmission
                    model["gitRepository"] = gitClient.convertSSHGithubURLtoHttpURL(gitSubmission.gitRepositoryUrl)
            }

            // check README
            val mavenizedProjectFolder = assignmentTeacherFiles.getProjectFolderAsFile(submission,
                    submission.getStatus() == SubmissionStatus.VALIDATED_REBUILT)
            if (File(mavenizedProjectFolder, "README.md").exists()) {
                val readmeContent = File(mavenizedProjectFolder, "README.md").readText()
                val parser = Parser.builder()
                        .extensions(listOf(AutolinkExtension.create()))
                        .build()
                val document = parser.parse(readmeContent)
                val renderer = HtmlRenderer.builder().build()
                model["readmeHTML"] = "<hr/>\n" + renderer.render(document) + "<hr/>\n"
            }

            // check the submission status
            when (submission.getStatus()) {
                SubmissionStatus.ILLEGAL_ACCESS -> model["error"] = i18n.getMessage("student.build-report.illegalAccess", null, currentLocale)
                SubmissionStatus.FAILED -> model["error"] = i18n.getMessage("student.build-report.failed", null, currentLocale)
                SubmissionStatus.ABORTED_BY_TIMEOUT -> model["error"] = i18n.getMessage("student.build-report.abortedByTimeout", arrayOf(MAVEN_MAX_EXECUTION_TIME), currentLocale)
                SubmissionStatus.TOO_MUCH_OUTPUT -> model["error"] = i18n.getMessage("student.build-report.tooMuchOutput", null, currentLocale)
                SubmissionStatus.DELETED -> model["error"] = i18n.getMessage("student.build-report.deleted", null, currentLocale)
                SubmissionStatus.SUBMITTED, SubmissionStatus.SUBMITTED_FOR_REBUILD, SubmissionStatus.REBUILDING -> {
                    model["error"] = i18n.getMessage("student.build-report.submitted", null, currentLocale)
                    model["autoRefresh"] = true
                }
                SubmissionStatus.VALIDATED, SubmissionStatus.VALIDATED_REBUILT -> {
                    val submissionReport = submissionReportRepository.findBySubmissionId(submission.id)

                    // fill the assignment in the reports
                    submissionReport.forEach { it.assignment = assignment }

                    model["summary"] = submissionReport
                    model["structureErrors"] = submission.structureErrors?.split(";") ?: emptyList<String>()

                    val authors = ArrayList<AuthorDetails>()
                    for (authorDB in submission.group.authors) {
                        authors.add(AuthorDetails(name = authorDB.name, number = authorDB.userId,
                                submitter = submission.submitterUserId == authorDB.userId))
                    }
                    model["authors"] = authors

                    submission.buildReportId?.let {
                        buildReportId ->
                            val buildReportDB = buildReportRepository.getById(buildReportId)
                            model["buildReport"] = buildReportBuilder.build(buildReportDB.buildReport.split("\n"),
                                    mavenizedProjectFolder.absolutePath, assignment, submission)
                    }
                }
            }
        }

        return "build-report"
    }

    /**
     * Controller that handles the download of a specific submission's code. The submission is downloaded in
     * a format compatible with Maven.
     * @param submissionId is a Long, representing the [Submission] to download
     * @param principal is a [Principal] representing the user making the request
     * @param request is a [HttpServletRequest]
     * @param response is a [HttpServletResponse]
     * @return A [FileSystemResource] containing a [ZipFile]
     */
    @RequestMapping(value = ["/downloadMavenProject/{submissionId}"],
            method = [(RequestMethod.GET)], produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @ResponseBody
    fun downloadMavenProject(@PathVariable submissionId: Long, principal: Principal,
                             request: HttpServletRequest, response: HttpServletResponse): FileSystemResource {

        val submission = submissionRepository.findById(submissionId).orElse(null)
        if (submission != null) {

            // check that principal belongs to the group that made this submission
            if (!request.isUserInRole("TEACHER")) {
                throw org.springframework.security.access.AccessDeniedException("${principal.realName()} is not allowed to view this report")
            }

            val projectFolder = assignmentTeacherFiles.getProjectFolderAsFile(submission,
                    wasRebuilt = submission.getStatus() == SubmissionStatus.VALIDATED_REBUILT)
            LOG.info("[${principal.realName()}] downloaded ${projectFolder.name}")

            val zipFilename = submission.group.authorsStr().replace(",", "_") + "_mavenized"
            val zipFile = zipService.createZipFromFolder(zipFilename, projectFolder)

            LOG.info("Created ${zipFile.file.absolutePath}")

            response.setHeader("Content-Disposition", "attachment; filename=${zipFilename}.zip")

            return FileSystemResource(zipFile.file)

        } else {
            throw ResourceNotFoundException()
        }
    }

    /**
     * Controller that handles the download of a specific submission's code. The submission is downloaded in
     * it's original format.
     * @param submissionId is a Long, representing the Submission to download
     * @param principal is a [Principal] representing the user making the request
     * @param request is an [HttpServletRequest]
     * @param response is an [HttpServletResponse]
     * @return A [FileSystemResource] containing a [ZipFile]
     */
    @RequestMapping(value = ["/downloadOriginalProject/{submissionId}"],
            method = [(RequestMethod.GET)], produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @ResponseBody
    fun downloadOriginalProject(@PathVariable submissionId: Long, principal: Principal,
                                request: HttpServletRequest, response: HttpServletResponse): FileSystemResource {

        val submission = submissionRepository.findById(submissionId).orElse(null)
        if (submission != null) {

            // check that principal belongs to the group that made this submission
            if (!request.isUserInRole("TEACHER")) {
                val groupElements = submission.group.authors
                if (groupElements.filter { it -> it.userId == principal.realName() }.isEmpty()) {
                    throw org.springframework.security.access.AccessDeniedException("${principal.realName()} is not allowed to view this report")
                }
            }

            if (submission.submissionId != null) {  // submission by upload
                val projectFolder = File(uploadSubmissionsRootLocation, submission.submissionFolder)
                val projectFile = File("${projectFolder.absolutePath}.zip")  // for every folder, there is a corresponding zip file with the same name

                LOG.info("[${principal.realName()}] downloaded ${projectFile.name}")

                val filename = submission.group.authorsStr().replace(",", "_")
                response.setHeader("Content-Disposition", "attachment; filename=${filename}.zip")

                return FileSystemResource(projectFile)
            } else {  // submission by git
                val gitSubmissionId = submission.gitSubmissionId ?:
                    throw IllegalArgumentException("Git submission without gitSubmissionId")
                val gitSubmission = gitSubmissionRepository.findById(gitSubmissionId).orElse(null)
                        ?: throw IllegalArgumentException("git submission ${submissionId} is not registered")
                val repositoryFolder = File(gitSubmissionsRootLocation, gitSubmission.getFolderRelativeToStorageRoot())

                val zipFilename = submission.group.authorsStr().replace(",", "_")
                val zFile = File.createTempFile(zipFilename, ".zip")
                if (zFile.exists()) {
                    zFile.delete();
                }
                val zipFile = ZipFile(zFile)
                val zipParameters = ZipParameters()
                zipParameters.isIncludeRootFolder = false
                zipParameters.compressionLevel = Zip4jConstants.DEFLATE_LEVEL_ULTRA
                zipFile.createZipFileFromFolder(repositoryFolder, zipParameters, false, -1)

                LOG.info("Created ${zipFile.file.absolutePath}")

                response.setHeader("Content-Disposition", "attachment; filename=${zipFilename}.zip")

                return FileSystemResource(zipFile.file)
            }

        } else {
            throw ResourceNotFoundException()
        }
    }

    /**
     * Controller that handles requests related with the download of ALL the students' submissions (code)
     * for a certain Assignment. The submissions are downloaded in their original format.
     * @param assignmentId is a String identifying the relevant Assignment
     * @param principal is a [Principal] representing the user making the request
     * @param response is an [HttpServletResponse]
     * @return A [FileSystemResource] containing a [ZipFile]
     */
    @RequestMapping(value = ["/downloadOriginalAll/{assignmentId}"],
            method = [(RequestMethod.GET)], produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @ResponseBody
    fun downloadOriginalAll(@PathVariable assignmentId: String, principal: Principal,
                            response: HttpServletResponse): FileSystemResource {

        val assignment = assignmentRepository.findById(assignmentId).orElse(null)
                ?: throw IllegalArgumentException("assignment ${assignmentId} is not registered")
        val acl = assignmentACLRepository.findByAssignmentId(assignmentId)

        if (principal.realName() != assignment.ownerUserId && acl.find { it -> it.userId == principal.realName() } == null) {
            throw IllegalAccessError("Assignment reports can only be accessed by their owner or authorized teachers")
        }

        if (assignment.submissionMethod != SubmissionMethod.UPLOAD) {
            throw IllegalArgumentException("downloadOriginalAll is only implemented for assignments whose submissions are through upload")
        }

        val submissionInfoList = submissionService.getSubmissionsList(assignment)

        val tempFolder = Files.createTempDirectory("dp-${assignmentId}").toFile()
        try {
            for (submissionInfo in submissionInfoList) {
                val projectFolder = File(tempFolder, submissionInfo.projectGroup.authorsStr("_"))
                projectFolder.mkdir()

                val submission = submissionInfo.lastSubmission

                val originalProjectFolder = storageService.retrieveProjectFolder(submission)
                        ?: throw IllegalArgumentException("projectFolder for ${submission.submissionId} doesn't exist")

                var hadToUnzip = false
                if (!originalProjectFolder.exists()) {
                    // let's check if there is a zip file with this project
                    val originalProjectZipFile = File("${originalProjectFolder.absolutePath}.zip")
                    if (originalProjectZipFile.exists()) {
                        zipService.unzip(Paths.get(originalProjectZipFile.path), originalProjectFolder.name)
                        hadToUnzip = true
                    }
                }

                LOG.info("Copying ${originalProjectFolder.absolutePath} to ${projectFolder.absolutePath}")

                FileUtils.copyDirectory(originalProjectFolder, projectFolder)

                if (hadToUnzip) {
                    originalProjectFolder.delete()
                }
            }

            val zipFilename = tempFolder.name
            val zipFile = zipService.createZipFromFolder(zipFilename, tempFolder)

            LOG.info("Created ${zipFile.file.absolutePath} with ${submissionInfoList.size} projects from ${assignmentId}")

            response.setHeader("Content-Disposition", "attachment; filename=${assignmentId}_last_submissions.zip")

            return FileSystemResource(zipFile.file)
        } finally {
            tempFolder.delete()
        }

    }

    /**
     * Controller that handles requests related with the download of ALL the students' submissions (code)
     * for a certain [Assignment]. The submissions are downloaded in a format compatible with Maven.
     * @param assignmentId is a String identifying the relevant Assignment
     * @param principal is a [Principal] representing the user making the request
     * @param response is an [HttpServletResponse]
     * @return A [FileSystemResource] containing a [ZipFile]
     */
    @RequestMapping(value = ["/downloadMavenizedAll/{assignmentId}"],
            method = [(RequestMethod.GET)], produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @ResponseBody
    fun downloadMavenizedAll(@PathVariable assignmentId: String, principal: Principal,
                             response: HttpServletResponse): FileSystemResource {

        val assignment = assignmentRepository.findById(assignmentId).orElse(null)
                ?: throw IllegalArgumentException("assignment ${assignmentId} is not registered")
        val acl = assignmentACLRepository.findByAssignmentId(assignmentId)

        if (principal.realName() != assignment.ownerUserId && acl.find { it -> it.userId == principal.realName() } == null) {
            throw IllegalAccessError("Assignment reports can only be accessed by their owner or authorized teachers")
        }

        val submissionInfoList = submissionService.getSubmissionsList(assignment)
        val modulesList = mutableListOf<String>()

        val tempFolder = Files.createTempDirectory("dp-mavenized-${assignmentId}").toFile()

        try {
            for (submissionInfo in submissionInfoList) {

                val submission = submissionInfo.lastSubmission
                val originalProjectFolder = assignmentTeacherFiles.getProjectFolderAsFile(submission,
                        wasRebuilt = submission.getStatus() == SubmissionStatus.VALIDATED_REBUILT)

                if (!originalProjectFolder.exists()) {
                    LOG.warn("${originalProjectFolder.absolutePath} doesn't exist. " +
                            "Probably, it has structure errors. This submission will not be included in the zip file.")
                } else {

                    val projectFolder = File(tempFolder, submissionInfo.projectGroup.authorsStr("_"))
                    projectFolder.mkdir()

                    LOG.info("Copying ${originalProjectFolder.absolutePath} to ${projectFolder.absolutePath}")

                    FileUtils.copyDirectory(originalProjectFolder, projectFolder) {
                        val relativePath = it.toRelativeString(originalProjectFolder)
                        !relativePath.startsWith("target")
                    }

                    // replace artifactId in pom.xml
                    val newPomFileContent = ArrayList<String>()
                    val pomFile = File(projectFolder, "pom.xml")
                    var firstArtifactIdLineFound = false
                    pomFile
                            .readLines()
                            .forEach {
                                newPomFileContent.add(
                                        if (!firstArtifactIdLineFound && it.contains("<artifactId>")) {
                                            val projectGroupStr = submissionInfo.projectGroup.authorsStr("_")
                                            modulesList.add(projectGroupStr)
                                            firstArtifactIdLineFound = true
                                            "    <artifactId>${assignmentId}-${projectGroupStr}</artifactId>"
                                        } else {
                                            it
                                        }
                                )
                            }

                    Files.write(pomFile.toPath(), newPomFileContent)
                }
            }

            // create aggregate pom
            val ctx = Context()
            ctx.setVariable("groupId", assignment.packageName)
            ctx.setVariable("artifactId", assignment.id)
            ctx.setVariable("modules", modulesList)
            try {
                templateEngine.process("download-all-pom", ctx, FileWriter(File(tempFolder, "pom.xml")))
            } catch (e: Exception) {
                println("e = ${e}")
            }

            val zipFilename = tempFolder.name
            val zipFile = zipService.createZipFromFolder(zipFilename, tempFolder)

            LOG.info("Created ${zipFile.file.absolutePath} with ${submissionInfoList.size} projects from ${assignmentId}")

            response.setHeader("Content-Disposition", "attachment; filename=${assignmentId}_last_mavenized_submissions.zip")

            return FileSystemResource(zipFile.file)
        } finally {
            tempFolder.delete()
        }

    }

    /**
     * Controller that handles requests for the submissions of the current user in a certain [Assigment].
     * @param assignmentId is a String identifying the relevant assignment
     * @param model is a [ModelMap] that will be populated with information to use in a View
     * @param principal is a [Principal] representing the user making the request. This is the user whose submissions
     * will be returned.
     * @param request is an [HttpServletRequest]
     * @return A String with the name of the relevant View
     */
    @RequestMapping(value = ["/mySubmissions"], method = [(RequestMethod.GET)])
    fun getMySubmissions(@RequestParam("assignmentId") assignmentId: String, model: ModelMap,
                         principal: Principal, request: HttpServletRequest): String {

        val assignment = assignmentRepository.findById(assignmentId).orElse(null)

        model["assignment"] = assignment
        model["numSubmissions"] = submissionRepository.countBySubmitterUserIdAndAssignmentId(principal.realName(), assignment.id)

        // TODO this is similar to getSubmissions: refactor
        val submissions = submissionRepository
                .findBySubmitterUserIdAndAssignmentId(principal.realName(), assignmentId)
                .filter { it.getStatus() != SubmissionStatus.DELETED }
        for (submission in submissions) {
            val reportElements = submissionReportRepository.findBySubmissionId(submission.id)
            submission.reportElements = reportElements
            submission.overdue = assignment.overdue(submission)
            submission.buildReportId?.let {
                buildReportId ->
                    val mavenizedProjectFolder = assignmentTeacherFiles.getProjectFolderAsFile(submission,
                            submission.getStatus() == SubmissionStatus.VALIDATED_REBUILT)
                    val buildReportDB = buildReportRepository.getById(buildReportId)
                    val buildReport = buildReportBuilder.build(buildReportDB.buildReport.split("\n"),
                            mavenizedProjectFolder.absolutePath, assignment, submission)
                    submission.ellapsed = buildReport.elapsedTimeJUnit()
                    submission.teacherTests = buildReport.junitSummaryAsObject()
            }
        }

        model["submissions"] = submissions

        return "submissions"
    }

    /**
     * Controller that handles requests related with listing [Submission]s of a certain [Assignment] and
     * [ProjectGroup].
     * @param assignmentId is a String identifying the relevant Assignment
     * @param groupId is a String identifying the relevant ProjectGroup
     * @param model is a [ModelMap] that will be populated with information to use in a View
     * @param principal is a [Principal] representing the user making the request
     * @param request is an [HttpServletRequest]
     * @return A String with the name of the relevant View
     */
    @RequestMapping(value = ["/submissions"], method = [(RequestMethod.GET)])
    fun getSubmissions(@RequestParam("assignmentId") assignmentId: String,
                       @RequestParam("groupId") groupId: Long,
                       model: ModelMap, principal: Principal,
                       request: HttpServletRequest): String {

        val assignment = assignmentRepository.findById(assignmentId).orElse(null)
        val group = projectGroupRepository.findById(groupId).orElse(null)

        model["assignment"] = assignment
        model["numSubmissions"] = submissionRepository.countBySubmitterUserIdAndAssignmentId(principal.realName(), assignment.id)

        val submissions = submissionRepository
                .findByGroupAndAssignmentIdOrderBySubmissionDateDescStatusDateDesc(group, assignmentId)
                .filter { it.getStatus() != SubmissionStatus.DELETED }
        for (submission in submissions) {
            val reportElements = submissionReportRepository.findBySubmissionId(submission.id)
            submission.reportElements = reportElements
            submission.overdue = assignment.overdue(submission)
            submission.buildReportId?.let {
                    buildReportId ->
                        val mavenizedProjectFolder = assignmentTeacherFiles.getProjectFolderAsFile(submission,
                                submission.getStatus() == SubmissionStatus.VALIDATED_REBUILT)
                        val buildReportDB = buildReportRepository.getById(buildReportId)
                        val buildReport = buildReportBuilder.build(buildReportDB.buildReport.split("\n"),
                                mavenizedProjectFolder.absolutePath, assignment, submission)
                        submission.ellapsed = buildReport.elapsedTimeJUnit()
                        submission.teacherTests = buildReport.junitSummaryAsObject(TestType.TEACHER)
                        submission.hiddenTests = buildReport.junitSummaryAsObject(TestType.HIDDEN)
            }
        }

        model["group"] = group
        model["submissions"] = submissions

        if (assignment.submissionMethod == SubmissionMethod.GIT && !submissions.isEmpty()) {
            submissions[0].gitSubmissionId?.let { gitSubmissionId ->
                val gitSubmission = gitSubmissionRepository.getById(gitSubmissionId)

                val repositoryFolder = File(gitSubmissionsRootLocation, gitSubmission.getFolderRelativeToStorageRoot())
                val history = gitClient.getHistory(repositoryFolder)
                model["gitHistory"] = history
                model["gitRepository"] = gitClient.convertSSHGithubURLtoHttpURL(gitSubmission.gitRepositoryUrl)
            }
        }

        return "submissions"
    }

    /**
     * Controller that handles the exportation of an Assignment's submission results to a CSV file.
     * @param assignmentId is a String, identifying the relevant Assignment
     * @return A ResponseEntity<String>
     */
    @RequestMapping(value = ["/exportCSV/{assignmentId}"], method = [(RequestMethod.GET)])
    fun exportCSV(@PathVariable assignmentId: String,
                  @RequestParam(name="ellapsed", defaultValue = "true") includeEllapsed: Boolean, principal: Principal): ResponseEntity<String> {

        val assignment = assignmentRepository.findById(assignmentId).orElse(null)
        val acl = assignmentACLRepository.findByAssignmentId(assignmentId)

        if (principal.realName() != assignment.ownerUserId && acl.find { it -> it.userId == principal.realName() } == null) {
            throw IllegalAccessError("Assignment reports can only be accessed by their owner or authorized teachers")
        }

        var headersCSV = LinkedHashSet(mutableListOf("submission id","student id","student name","project structure", "compilation", "code quality"))
        var resultCSV = ""

        val submissions = submissionRepository.findByAssignmentIdAndMarkedAsFinal(assignmentId, true)
                .filter { it.getStatus() != SubmissionStatus.DELETED }
        for (submission in submissions) {
            val reportElements = submissionReportRepository.findBySubmissionId(submission.id)
            submission.reportElements = reportElements
            submission.overdue = assignment.overdue(submission)
            submission.buildReportId?.let {
                buildReportId ->
                    val mavenizedProjectFolder = assignmentTeacherFiles.getProjectFolderAsFile(submission,
                            submission.getStatus() == SubmissionStatus.VALIDATED_REBUILT)
                    val buildReportDB = buildReportRepository.getById(buildReportId)
                    val buildReport = buildReportBuilder.build(buildReportDB.buildReport.split("\n"),
                            mavenizedProjectFolder.absolutePath, assignment, submission)
                    submission.ellapsed = buildReport.elapsedTimeJUnit()
                    if (assignment.acceptsStudentTests) {
                        submission.studentTests = buildReport.junitSummaryAsObject(TestType.STUDENT)
                    }
                    submission.teacherTests = buildReport.junitSummaryAsObject(TestType.TEACHER)
                    submission.hiddenTests = buildReport.junitSummaryAsObject(TestType.HIDDEN)
                    if (assignment.calculateStudentTestsCoverage && buildReport.jacocoResults.isNotEmpty()) {
                        submission.coverage = buildReport.jacocoResults[0].lineCoveragePercent
                    }
            }
        }

        val hasTeacherTests = submissions.any { it.teacherTests != null }
        val hasHiddenTests = submissions.any { it.hiddenTests != null }

        for (submission in submissions) {
            val r1 = submission.reportElements?.getOrNull(0)?.reportValue.orEmpty()  // Project Structure
            val r2 = submission.reportElements?.getOrNull(1)?.reportValue.orEmpty()  // Compilation
            val r3 = submission.reportElements?.getOrNull(2)?.reportValue.orEmpty()  // Code Quality

            var ellapsed = submission.ellapsed
            if (ellapsed != null) {
                ellapsed = ellapsed.setScale(2, RoundingMode.UP)
            }

            for (author in submission.group.authors) {
                resultCSV += "${submission.group.id};${author.userId};${author.name};${r1};${r2};${r3};"

                if (assignment.acceptsStudentTests) {
                    headersCSV.add("student tests")
                    if (submission.studentTests != null) {
                        resultCSV += "${submission.studentTests!!.progress};"
                    } else {
                        resultCSV += ";"
                    }
                }

                if (hasTeacherTests) {
                    headersCSV.add("teacher tests")
                    resultCSV += if (submission.teacherTests != null) "${submission.teacherTests!!.progress};" else ";"
                }

                if (hasHiddenTests) {
                    headersCSV.add("hidden tests")
                    resultCSV += if (submission.hiddenTests != null) "${submission.hiddenTests!!.progress};" else ";"
                }

                if (assignment.calculateStudentTestsCoverage) {
                    headersCSV.add("coverage")
                    resultCSV += if (submission.coverage != null) "${submission.coverage};" else ";"
                }

                if (includeEllapsed) {
                    headersCSV.add("ellapsed")
                    resultCSV += "${ellapsed?.toPlainString().orEmpty()};"
                }

                headersCSV.add("submission date")
                resultCSV += submission.submissionDate.formatDefault() + ";"

                headersCSV.add("# submissions")
                resultCSV += submissionRepository.countByAssignmentIdAndSubmitterUserId(submission.assignmentId, author.userId)

                if (assignment.mandatoryTestsSuffix != null) {
                    headersCSV.add("# mandatory")
                    resultCSV += ";" + (submission.teacherTests?.numMandatoryOK ?: 0)
                }

                headersCSV.add("overdue")
                resultCSV += ";" + submission.overdue

                resultCSV += "\n"
            }
        }

        resultCSV = headersCSV.joinToString(";") + "\n" + resultCSV

        val headers = HttpHeaders()
        headers.contentType = MediaType("application", "csv")
        headers.setContentDispositionFormData("attachment", "${assignmentId}_final_results.csv");
        return ResponseEntity(resultCSV, headers, HttpStatus.OK);
    }

    /**
     * Controller that handles requests for an [Assignment]'s Leaderboard.
     * @param assignmentId is a String identifying the relevant Assignment
     * @param model is a [ModelMap] that will be populated with information to use in a View
     * @param principal is a [Principal] representing the user making the request
     * @param request is an [HttpServletRequest]
     * @return A String with the name of the relevant View
     */
    @RequestMapping(value = ["/leaderboard/{assignmentId}"], method = [(RequestMethod.GET)])
    fun getLeaderboard(@PathVariable assignmentId: String, model: ModelMap,
                       principal: Principal, request: HttpServletRequest): String {

        val assignment = assignmentRepository.findById(assignmentId).orElse(null)
        if (!assignment.showLeaderBoard) {
            throw AccessDeniedException("Leaderboard for this assignment is not turned on")
        } else {
            if (assignment.leaderboardType == null) {  // TODO: Remove this after making this field mandatory
                assignment.leaderboardType = LeaderboardType.TESTS_OK
            }
        }

        val submissionInfoList = submissionService.getSubmissionsList(assignment)

        val comparator: Comparator<Submission> =
                when (assignment.leaderboardType ?: LeaderboardType.TESTS_OK) {
                    LeaderboardType.TESTS_OK -> compareBy { -it.teacherTests!!.progress }
                    LeaderboardType.ELLAPSED -> compareBy({ -it.teacherTests!!.progress }, { it.ellapsed })
                    LeaderboardType.COVERAGE -> compareBy({ -it.teacherTests!!.progress }, { -(it.coverage ?: 0) })
                }

        val sortedList =
                submissionInfoList
                        .map { it.lastSubmission }
                        .filter { it.getStatus() in listOf(SubmissionStatus.VALIDATED, SubmissionStatus.VALIDATED_REBUILT) }
                        .filter { it.teacherTests?.progress ?: 0 > 0 }
                        // compare by progress descending and ellapsed ascending
                        .sortedWith( comparator )
                        .map {
                            it.reportElements = it.reportElements?.filter { it.indicator == Indicator.TEACHER_UNIT_TESTS }
                            it  // just return itself
                        }

        model["assignment"] = assignment
        model["submissions"] = sortedList

        return "leaderboard"
    }

    @RequestMapping(value = ["/studentHistoryForm"], method = [(RequestMethod.GET)])
    fun getStudentHistoryForm(): String {
        return "student-history-form"
    }

    // TODO: Pass this to the APIController in the future
    data class StudentListResponse(val value: String, val text: String)
    @RequestMapping(value = ["/studentList"], method = [(RequestMethod.GET)], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStudentList(@RequestParam("q") q: String): ResponseEntity<List<StudentListResponse>> {

        val result = authorRepository.findAll()
            .filter { it.name.lowercase().contains(q.lowercase()) || it.userId.lowercase().contains(q.lowercase())}
            .distinctBy { it.userId }
            .map { StudentListResponse(it.userId, it.name) }

        return ResponseEntity(result, HttpStatus.OK)
    }

    // For now, this will list assignments even if the teacher making the request
    // does not have access to those assignments
    @RequestMapping(value = ["/studentHistory"], method = [(RequestMethod.GET)])
    fun getStudentHistory(@RequestParam("id") studentId: String, model: ModelMap,
                       principal: Principal, request: HttpServletRequest): String {

        if (!request.isUserInRole("TEACHER")) {
            throw AccessDeniedException("${principal.realName()} is not allowed to view this report")
        }

        val authorGroups = authorRepository.findByUserId(studentId);

        if (authorGroups == null) {
            model["message"] = "Student with id $studentId does not exist"
            return "student-history"
        }

        val projectGroups = projectGroupRepository.getGroupsForAuthor(studentId)

        // since there may be several authors (same student in different groups), we'll just choose the
        // first one, since the goals is to just get his name
        val studentHistory = StudentHistory(authorGroups[0])

        // store assignments on hashmap for performance reasons
        // the key is a pair (assignmentId, groupId), since a student can participate
        // in the same assignment with different groups
        val assignmentsMap = HashMap<Pair<String,Long>,Assignment>()

        val submissions = submissionRepository.findByGroupIn(projectGroups)
        val submissionIds = submissions.map { it.id }
        val submissionReports = submissionReportRepository.findBySubmissionIdIn(submissionIds)
        for (submission in submissions) {
            // fill indicators
            submission.reportElements = submissionReports.filter { it.submissionId == submission.id }

            val assignmentAndGroup = Pair(submission.assignmentId, submission.group.id)
            if (!assignmentsMap.containsKey(assignmentAndGroup)) {
                val assignment = assignmentRepository.findById(submission.assignmentId).get()
                assignmentsMap[assignmentAndGroup] = assignment;
                studentHistory.addGroupAndAssignment(submission.group, assignment)
            }

            studentHistory.addSubmission(submission)
        }

        studentHistory.ensureSubmissionsAreSorted()

        // 1- gather all assignments
        // 2- where was the student signalleed?
        // 3- students he works it

        //model["submissions"] = submissions
        //model["assignments"] = assignments
        model["studentHistory"] = studentHistory

        return "student-history";
    }

    @RequestMapping(value = ["/migrate/{idx1}/{idx2}"], method = [(RequestMethod.GET)])
    fun migrate(@PathVariable idx1: Long, @PathVariable idx2: Long) {

//        val submissions = submissionRepository.findByIdBetween(idx1, idx2)
//
//        for (submission in submissions) {
//            if (submission.buildReport != null) {
//                val buildReport = BuildReport(buildReport = submission.buildReport!!)
//                buildReportRepository.save(buildReport)
//
//                submission.buildReportId = buildReport.id
//                submissionRepository.save(submission)
//            }
//        }


//        val assignments = assignmentRepository.findAll()
//
//        for (assignment in assignments) {
//
//            if (assignment.buildReportId != null) {
//                val buildReport = BuildReport(buildReport = assignment.buildReport!!)
//                buildReportRepository.save(buildReport)
//
//                assignment.buildReportId = buildReport.id
//                assignmentRepository.save(assignment)
//            }
//
//        }
    }

//    @RequestMapping(value = ["/size"], method = [(RequestMethod.GET)])
//    fun size() {
//
//        val assignments = assignmentRepository.findAll()
//
//        for (assignment in assignments) {
//
//            var countDeletedFolders = 0
//            var countExistentFolders = 0
//            var totalSize = 0L
//
//            val submissions = submissionRepository.findByAssignmentId(assignment.id)
//
//            for (submission in submissions) {
//                val mavenizedFolder = File(mavenizedProjectsRootLocation + "/" + submission.submissionId + "-mavenized")
//                if (mavenizedFolder.exists()) {
//                    countExistentFolders++
//                    totalSize += FileUtils.sizeOfDirectory(mavenizedFolder)
//                } else {
//                    countDeletedFolders++
//                }
//            }
//
//            LOG.info("*** ${assignment.id} - ${countExistentFolders} - ${countDeletedFolders} (${totalSize / 1_000_000} Mb) ***")
//        }
//
//    }

}
