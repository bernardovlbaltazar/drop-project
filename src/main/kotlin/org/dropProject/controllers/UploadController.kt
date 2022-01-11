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

import org.apache.commons.io.FileUtils
import org.springframework.stereotype.Controller
import org.springframework.ui.ModelMap
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.dropProject.forms.UploadForm
import org.dropProject.storage.StorageException
import org.dropProject.storage.StorageService
import java.io.File
import javax.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.dropProject.data.AuthorDetails
import java.security.Principal
import java.util.*
import java.util.logging.Logger
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.EnableAsync
import org.apache.any23.encoding.TikaEncodingDetector
import org.dropProject.Constants
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.RefNotAdvertisedException
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.security.access.AccessDeniedException
import org.dropProject.dao.*
import org.dropProject.extensions.existsCaseSensitive
import org.dropProject.extensions.sanitize
import org.dropProject.extensions.realName
import org.dropProject.forms.SubmissionMethod
import org.dropProject.repository.*
import org.dropProject.services.*
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import java.io.IOException
import java.io.InputStream
import java.lang.IllegalStateException
import java.nio.charset.Charset
import java.nio.charset.UnsupportedCharsetException
import java.nio.file.Paths
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executor
import java.util.logging.Level
import javax.servlet.http.HttpServletRequest

/**
 * UploadController is an MVC controller class to handle requests related with the upload of submissions.
 */
@Controller
@EnableAsync
class UploadController(
        val storageService: StorageService,
        val buildWorker: BuildWorker,
        val authorRepository: AuthorRepository,
        val projectGroupRepository: ProjectGroupRepository,
        val submissionRepository: SubmissionRepository,
        val gitSubmissionRepository: GitSubmissionRepository,
        val submissionReportRepository: SubmissionReportRepository,
        val assignmentRepository: AssignmentRepository,
        val assignmentACLRepository: AssignmentACLRepository,
        val assigneeRepository: AssigneeRepository,
        val asyncExecutor: Executor,
        val assignmentTeacherFiles: AssignmentTeacherFiles,
        val gitClient: GitClient,
        val gitSubmissionService: GitSubmissionService,
        val submissionService: SubmissionService,
        val zipService: ZipService,
        val projectGroupService: ProjectGroupService,
        val i18n: MessageSource
        ) {

    @Value("\${storage.rootLocation}/git")
    val gitSubmissionsRootLocation : String = "submissions/git"

    @Value("\${mavenizedProjects.rootLocation}")
    val mavenizedProjectsRootLocation : String = ""

    @Value("\${delete.original.projectFolder:true}")
    val deleteOriginalProjectFolder : Boolean = true

    @Value("\${spring.web.locale}")
    val currentLocale : Locale = Locale.getDefault()

    val LOG = LoggerFactory.getLogger(this.javaClass.name)

    init {
        storageService.init()
    }

    /**
     * Controller that handles related with the base URL.
     *
     * If the principal can only access one [Assignment], then that assignment's upload form will be displayed. Otherwise,
     * a list of assignments will be displayed.
     *
     * @param model is a [ModelMap] that will be populated with the information to use in a View
     * @param principal is a [Principal] representing the user making the request
     * @param request is a HttpServletRequest
     *
     * @return A String identifying the relevant View
     */
    @RequestMapping(value = ["/"], method = [(RequestMethod.GET)])
    fun getUploadForm(model: ModelMap, principal: Principal, request: HttpServletRequest): String {

        val assignments = ArrayList<Assignment>()

        val assignees = assigneeRepository.findByAuthorUserId(principal.realName())
        for (assignee in assignees) {
            val assignment = assignmentRepository.getById(assignee.assignmentId)
            if (assignment.active == true) {
                assignments.add(assignment)
            }
        }

        if (request.isUserInRole("TEACHER")) {
            val assignmentsIOwn = assignmentRepository.findByOwnerUserId(principal.realName())

            for (assignmentIOwn in assignmentsIOwn) {
                if (!assignmentIOwn.archived) {
                    assignments.add(assignmentIOwn)
                }
            }
        }

        if (assignments.size == 1) {
            // redirect to that assignment
            return "redirect:/upload/${assignments[0].id}"
        }

        model["assignments"] = assignments

        return "student-assignments-list"
    }


    /**
     * Controller that handles requests related with the [Assignment]'s upload page.
     *
     * @param model is a [ModelMap] that will be populated with the information to use in a View
     * @param principal is a [Principal] representing the user making the request
     * @param assignmentId is a String, identifying the relevant Assigment
     * @param request is an HttpServletRequest
     *
     * @return A String identifying the relevant View
     */
    @RequestMapping(value = ["/upload/{assignmentId}"], method = [(RequestMethod.GET)])
    fun getUploadForm(model: ModelMap, principal: Principal,
                      @PathVariable("assignmentId") assignmentId: String,
                      request: HttpServletRequest): String {

        val assignment = assignmentRepository.findById(assignmentId).orElse(null) ?: throw AssignmentNotFoundException(assignmentId)

        if (!request.isUserInRole("TEACHER")) {

            if (!assignment.active) {
                throw org.springframework.security.access.AccessDeniedException("Submissions are not open to this assignment")
            }

            checkAssignees(assignmentId, principal.realName())

        } else {

            if (!assignment.active) {
                val acl = assignmentACLRepository.findByAssignmentId(assignmentId)
                if (principal.realName() != assignment.ownerUserId && acl.find { it -> it.userId == principal.realName() } == null) {
                    throw IllegalAccessError("Assignments can only be accessed by their owner or authorized teachers")
                }
            }
        }

        model["assignment"] = assignment
        model["numSubmissions"] = submissionRepository.countBySubmitterUserIdAndAssignmentId(principal.realName(), assignment.id)
        model["instructionsFragment"] = assignmentTeacherFiles.getHtmlInstructionsFragment(assignment)
        model["packageTree"] = assignmentTeacherFiles.buildPackageTree(
                assignment.packageName, assignment.language, assignment.acceptsStudentTests)

        if (assignment.cooloffPeriod != null && !request.isUserInRole("TEACHER")) {
            val lastSubmission = getLastSubmission(principal, assignmentId)
            if (lastSubmission != null) {
                val nextSubmissionTime = calculateCoolOff(lastSubmission, assignment)
                if (nextSubmissionTime != null) {
                    model["coolOffEnd"] = nextSubmissionTime.format(DateTimeFormatter.ofPattern("HH:mm"))
                    LOG.info("[${principal.realName()}] can't submit because he is in cool-off period")
                }
            }
        }

        if (assignment.submissionMethod == SubmissionMethod.UPLOAD) {

            val submission = submissionRepository.findFirstBySubmitterUserIdAndAssignmentIdOrderBySubmissionDateDesc(principal.realName(), assignmentId)

            model["uploadForm"] = UploadForm(assignment.id)
            model["uploadSubmission"] = submission
            return "student-upload-form"
        } else {

            val gitSubmission =
                    gitSubmissionRepository.findBySubmitterUserIdAndAssignmentId(principal.realName(), assignmentId)
                    ?:
                    gitSubmissionService.findGitSubmissionBy(principal.realName(), assignmentId) // check if it belongs to a group who has already a git submission

            if (gitSubmission?.connected == true) {
                // get last commit info
                val git = Git.open(File(gitSubmissionsRootLocation, gitSubmission.getFolderRelativeToStorageRoot()))
                val lastCommitInfo = gitClient.getLastCommitInfo(git)
                model["lastCommitInfo"] = lastCommitInfo
            }

            model["gitSubmission"] = gitSubmission

            return "student-git-form"
        }


    }


    /**
     * Controller that handles requests for the actual file upload that delivers/submits the student's code.
     *
     * @param uploadForm is an [Uploadform]
     * @param bindingResult is a [BindingResult]
     * @param file is a [MultipartFile]
     * @param principal is a [Principal] representing the user making the request
     * @param request is an HttpServletRequest
     *
     * @return a ResponseEntity<String>
     */
    @RequestMapping(value = ["/upload"], method = [(RequestMethod.POST)])
    fun upload(@Valid @ModelAttribute("uploadForm") uploadForm: UploadForm,
               bindingResult: BindingResult,
               @RequestParam("file") file: MultipartFile,
               principal: Principal,
               request: HttpServletRequest): ResponseEntity<String> {

        if (bindingResult.hasErrors()) {
            return ResponseEntity("{\"error\": \"Internal error\"}", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        if (uploadForm.assignmentId == null) {
            throw IllegalArgumentException("assignmentId is null")
        }

        val assignmentId = uploadForm.assignmentId ?:
            throw IllegalArgumentException("Upload form is missing the assignmentId")

        val assignment = assignmentRepository.findById(assignmentId).orElse(null) ?:
                throw IllegalArgumentException("assignment ${assignmentId} is not registered")

        if (assignment.submissionMethod != SubmissionMethod.UPLOAD) {
            throw IllegalArgumentException("this assignment doesnt accept upload submissions")
        }

        // TODO: Validate assignment due date

        if (!request.isUserInRole("TEACHER")) {

            if (!assignment.active) {
                throw AccessDeniedException("Submissions are not open to this assignment")
            }

            checkAssignees(uploadForm.assignmentId!!, principal.realName())
        }

        if (assignment.cooloffPeriod != null  && !request.isUserInRole("TEACHER")) {
            val lastSubmission = getLastSubmission(principal, assignment.id)
            if (lastSubmission != null) {
                val nextSubmissionTime = calculateCoolOff(lastSubmission, assignment)
                if (nextSubmissionTime != null) {
                    LOG.warn("[${principal.realName()}] can't submit because he is in cool-off period")
                    throw AccessDeniedException("[${principal.realName()}] can't submit because he is in cool-off period")
                }
            }
        }

        val originalFilename = file.originalFilename ?:
            throw IllegalArgumentException("Missing originalFilename")

        if (!originalFilename.endsWith(".zip", ignoreCase = true)) {
            return ResponseEntity("{\"error\": \"O ficheiro tem que ser um .zip\"}", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        LOG.info("[${principal.realName()}] uploaded ${originalFilename}")
        val projectFolder : File? = storageService.store(file, assignment.id)

        if (projectFolder != null) {
            val authors = getProjectAuthors(projectFolder)
            LOG.info("[${authors.joinToString(separator = "|")}] Received ${originalFilename}")

            // check if the principal is one of group elements
            if (authors.filter { it.number == principal.realName() }.isEmpty()) {
                throw InvalidProjectStructureException(i18n.getMessage("student.submit.notAGroupElement", null, currentLocale))
            }

            val group = projectGroupService.getOrCreateProjectGroup(authors)

            // verify that there is not another submission with the Submitted status
            val existingSubmissions = submissionRepository
                    .findByGroupAndAssignmentIdOrderBySubmissionDateDescStatusDateDesc(group, assignment.id)
                    .filter { it.getStatus() != SubmissionStatus.DELETED }
            for (submission in existingSubmissions) {
                if (submission.getStatus() == SubmissionStatus.SUBMITTED) {
                    LOG.info("[${authors.joinToString(separator = "|")}] tried to submit before the previous one has been validated")
                    return ResponseEntity("{\"error\": \"${i18n.getMessage("student.submit.pending", null, currentLocale)}\"}", HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }

            val submission = Submission(submissionId = projectFolder.name, submissionDate = Date(),
                    status = SubmissionStatus.SUBMITTED.code, statusDate = Date(), assignmentId = assignment.id,
                    submitterUserId = principal.realName(),
                    submissionFolder = projectFolder.relativeTo(storageService.rootFolder()).path)
            submission.group = group
            submissionRepository.save(submission)

            buildSubmission(projectFolder, assignment, authors.joinToString(separator = "|"), submission, asyncExecutor, principal = principal)

            return ResponseEntity("{ \"submissionId\": \"${submission.id}\"}", HttpStatus.OK);
        }

        return ResponseEntity("{\"error\": \"${i18n.getMessage("student.submit.fileError", null, currentLocale)}\"}", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Builds and tests a [Submission].
     *
     * @property projectFolder is a File
     * @property assignment is the [Assignment] for which the submission is being made
     * @property authorsStr is a String
     * @property submission is a Submission
     * @property asyncExecutor is an Executor
     * @property teacherRebuid is a Boolean, indicating if this "build" is being requested by a teacher
     * @property principal is a [Principal] representing the user making the request
     */
    private fun buildSubmission(projectFolder: File, assignment: Assignment,
                                authorsStr: String,
                                submission: Submission,
                                asyncExecutor: Executor,
                                teacherRebuild: Boolean = false,
                                principal: Principal?) {
        val projectStructureErrors = checkProjectStructure(projectFolder, assignment)
        if (!projectStructureErrors.isEmpty()) {
            LOG.info("[${authorsStr}] Project Structure NOK")
            submissionReportRepository.save(SubmissionReport(submissionId = submission.id,
                    reportKey = Indicator.PROJECT_STRUCTURE.code, reportValue = "NOK"))
            submission.structureErrors = projectStructureErrors.joinToString(separator = ";")
            submission.setStatus(SubmissionStatus.VALIDATED)
            submissionRepository.save(submission)
        } else {
            LOG.info("[${authorsStr}] Project Structure OK")
            submissionReportRepository.save(SubmissionReport(submissionId = submission.id,
                    reportKey = Indicator.PROJECT_STRUCTURE.code, reportValue = "OK"))

            val mavenizedProjectFolder = mavenize(projectFolder, submission, assignment, teacherRebuild)
            LOG.info("[${authorsStr}] Mavenized to folder ${mavenizedProjectFolder}")

            if (asyncExecutor is ThreadPoolTaskScheduler) {
                LOG.info("asyncExecutor.activeCount = ${asyncExecutor.activeCount}")
            }

            if (teacherRebuild) {
                submission.setStatus(SubmissionStatus.REBUILDING, dontUpdateStatusDate = true)
                submissionRepository.save(submission)
            }

            buildWorker.checkProject(mavenizedProjectFolder, authorsStr, submission, rebuildByTeacher = teacherRebuild,
            principalName = principal?.name)
        }
    }


    private fun checkProjectStructure(projectFolder: File, assignment: Assignment): List<String> {
        val erros = ArrayList<String>()
        if (!File(projectFolder, "src").existsCaseSensitive()) {
            erros.add("O projecto não contém uma pasta 'src' na raiz")
        }

        val packageName = assignment.packageName.orEmpty().replace(".","/")

        if (!File(projectFolder, "src/${packageName}").existsCaseSensitive()) {
            erros.add("O projecto não contém uma pasta 'src/${packageName}'")
        }

        val mainFile = if (assignment.language == Language.JAVA) "Main.java" else "Main.kt"
        if (!File(projectFolder, "src/${packageName}/${mainFile}").existsCaseSensitive()) {
            erros.add("O projecto não contém o ficheiro ${mainFile} na pasta 'src/${packageName}'")
        }

        if (File(projectFolder, "src")
                .walkTopDown()
                .find { it.name.startsWith("TestTeacher") } != null) {
            erros.add("O projecto contém ficheiros cujo nome começa por 'TestTeacher'")
        }

        val readme = File(projectFolder, "README.md")
        if (readme.exists() && !readme.isFile) {
            erros.add("O projecto contém uma pasta README.md mas devia ser um ficheiro")
        }

        return erros
    }

    /**
     * Transforms a student's submission/code from its original structure to a structure that respects Maven's
     * expected format.
     * @param projectFolder is a file
     * @param submission is a Submission
     * @param teacherRebuild is a Boolean
     * @return File
     */
    private fun mavenize(projectFolder: File, submission: Submission, assignment: Assignment, teacherRebuild: Boolean = false): File {
        val mavenizedProjectFolder = assignmentTeacherFiles.getProjectFolderAsFile(submission, teacherRebuild)

        mavenizedProjectFolder.deleteRecursively()

        val folder = if (assignment.language == Language.JAVA) "java" else "kotlin"

        // first copy the project files submitted by the students
        FileUtils.copyDirectory(File(projectFolder, "src"), File(mavenizedProjectFolder, "src/main/${folder}")) {
            it.isDirectory || (it.isFile() && !it.name.startsWith("Test")) // exclude TestXXX classes
        }
        if (assignment.acceptsStudentTests) {
            FileUtils.copyDirectory(File(projectFolder, "src"), File(mavenizedProjectFolder, "src/test/${folder}")) {
                it.isDirectory || (it.isFile() && it.name.startsWith("Test")) // include TestXXX classes
            }
        }

        val testFilesFolder = File(projectFolder, "test-files")
        if (testFilesFolder.exists()) {
            FileUtils.copyDirectory(File(projectFolder, "test-files"), File(mavenizedProjectFolder, "test-files"))
        }
        FileUtils.copyFile(File(projectFolder, "AUTHORS.txt"),File(mavenizedProjectFolder, "AUTHORS.txt"))
        if (submission.gitSubmissionId == null && deleteOriginalProjectFolder) {  // don't delete git submissions
            FileUtils.deleteDirectory(projectFolder)  // TODO: This seems duplicate with the lines below...
        }

        // next, copy the project files submitted by the teachers (will override eventually the student files)
        assignmentTeacherFiles.copyTeacherFilesTo(assignment, mavenizedProjectFolder)

        // if the students have a README file, copy it over the teacher's README
        if (File(projectFolder, "README.md").exists()) {
            FileUtils.copyFile(File(projectFolder, "README.md"), File(mavenizedProjectFolder, "README.md"))
        }

        // finally remove the original project folder (the zip file is still kept)
        if (!(assignment.id.startsWith("testJavaProj") ||
                        assignment.id.startsWith("sample") ||
                        assignment.id.startsWith("testKotlinProj") ||  // exclude projects used for automatic tests
                        submission.gitSubmissionId != null)) {   // exclude git submissions
            projectFolder.deleteRecursively()
        }

        return mavenizedProjectFolder
    }

    private fun getProjectAuthors(projectFolder: File) : List<AuthorDetails> {
        // check for AUTHORS.txt file
        val authorsFile = File(projectFolder, "AUTHORS.txt")
        if (!authorsFile.existsCaseSensitive()) {
            throw InvalidProjectStructureException("O projecto não contém o ficheiro AUTHORS.txt na raiz")
        }

        // check the encoding of AUTHORS.txt
        val charset = try { guessCharset(authorsFile.inputStream()) } catch (ie: IOException) { Charset.defaultCharset() }
        if (!charset.equals(Charset.defaultCharset())) {
            LOG.info("AUTHORS.txt is not in the default charset (${Charset.defaultCharset()}): ${charset}")
        }

        // TODO check that AUTHORS.txt includes the number and name of the students
        val authors = ArrayList<AuthorDetails>()
        val authorIDs = HashSet<String>()
        try {
            authorsFile.readLines(charset = charset)
                    .map { line -> line.split(";") }
                    .forEach { parts -> run {
                        if (parts[1][0].isDigit() || parts[1].split(" ").size <= 1) {
                            throw InvalidProjectStructureException("Cada linha tem que ter o formato NUMERO_ALUNO;NOME_ALUNO. " +
                                    "O nome do aluno deve incluir o primeiro e último nome.")
                        }

                        authors.add(AuthorDetails(parts[1], parts[0].sanitize()))
                        authorIDs.add(parts[0])
                    } }

            // check for duplicate authors
            if (authorIDs.size < authors.size) {
                throw InvalidProjectStructureException("O ficheiro AUTHORS.txt não está correcto. " +
                "Contém autores duplicados.")
            }

        } catch (e: Exception) {
            when(e) {
                is InvalidProjectStructureException -> throw  e
                else -> {
                    LOG.debug("Error parsing AUTHORS.txt", e)
                    authors.clear()
                }
            }
        }

        if (authors.isEmpty()) {
            throw InvalidProjectStructureException("O ficheiro AUTHORS.txt não está correcto. " +
                    "Tem que conter uma linha por aluno, tendo cada linha o número e o nome separados por ';'")
        } else {
            return authors
        }
    }

    /**
     * Controller that handles requests for the [Submission]'s rebuild process. The rebuild process is a process where by
     * a student's submission gets compiled and evaluated again. It can be useful, for example, in situations where an
     * error was detected in the teacher's tests and the teacher wants to apply corrected tests to the student's submission.
     *
     * @param submissionId is a Long, identifying the student's Submission
     * @param principal is a [Principal] representing the user making the request
     *
     * @return a String identifying the relevant View
     */
    @RequestMapping(value = ["/rebuild/{submissionId}"], method = [(RequestMethod.POST)])
    fun rebuild(@PathVariable submissionId: Long,
                principal: Principal) : String {

        LOG.info("Rebuilding ${submissionId}")

        val submission = submissionRepository.findById(submissionId).orElse(null)
        val mavenizedProjectFolder = assignmentTeacherFiles.getProjectFolderAsFile(submission, wasRebuilt = false)

        // TODO: This should go into submission
        var authors = ArrayList<AuthorDetails>()
        for (authorDB in submission.group.authors) {
            authors.add(AuthorDetails(name = authorDB.name, number = authorDB.userId,
                    submitter = submission.submitterUserId == authorDB.userId))
        }

        submission.setStatus(SubmissionStatus.REBUILDING, dontUpdateStatusDate = true)
        submissionRepository.save(submission)

        buildWorker.checkProject(mavenizedProjectFolder, authors.joinToString(separator = "|"), submission,
                dontChangeStatusDate = true,
                principalName = principal.realName())

        return "redirect:/buildReport/${submissionId}";
    }

    // reaplies the assignment files, which may have changed since the submission
    @RequestMapping(value = ["/rebuildFull/{submissionId}"], method = [(RequestMethod.POST)])
    fun rebuildFull(@PathVariable submissionId: Long,
                principal: Principal) : String {

        LOG.info("Rebuilding full ${submissionId}")

        val submission = submissionRepository.findById(submissionId).orElse(null) ?: throw SubmissionNotFoundException(submissionId)

        val assignment = assignmentRepository.getById(submission.assignmentId)

        // create another submission that is a clone of this one, to preserve the original submission
        val rebuiltSubmission = Submission(submissionId = submission.submissionId,
                gitSubmissionId = submission.gitSubmissionId,
                submissionDate = submission.submissionDate,
                submitterUserId = submission.submitterUserId,
                assignmentId = submission.assignmentId,
                submissionFolder = submission.submissionFolder,
                status = SubmissionStatus.SUBMITTED_FOR_REBUILD.code,
                statusDate = Date())
        rebuiltSubmission.group = submission.group

        val projectFolder =
            if (submission.submissionId != null) {  // submission through upload

                val projectFolder = storageService.retrieveProjectFolder(rebuiltSubmission)
                        ?: throw IllegalArgumentException("projectFolder for ${rebuiltSubmission.submissionId} doesn't exist")

                LOG.info("Retrieved project folder: ${projectFolder.absolutePath}")

                if (!projectFolder.exists()) {
                    // let's check if there is a zip file with this project
                    val projectZipFile = File("${projectFolder.absolutePath}.zip")
                    if (projectZipFile.exists()) {
                        zipService.unzip(Paths.get(projectZipFile.path), projectFolder.name)
                    }
                }

                projectFolder

            } else if (submission.gitSubmissionId != null) {   // submission through git
                val gitSubmissionId = submission.gitSubmissionId ?: throw RuntimeException("Not possible")
                val gitSubmission = gitSubmissionRepository.findById(gitSubmissionId).orElse(null) ?:
                                        throw SubmissionNotFoundException(submission.gitSubmissionId!!)

                File(gitSubmissionsRootLocation, gitSubmission.getFolderRelativeToStorageRoot())

            } else {
                throw IllegalStateException("submission ${submission.id} has both submissionId and gitSubmissionId equal to null")
            }

        val authors = getProjectAuthors(projectFolder)

        submissionRepository.save(rebuiltSubmission)
        buildSubmission(projectFolder, assignment, authors.joinToString(separator = "|"), rebuiltSubmission,
                asyncExecutor, teacherRebuild = true, principal = principal)

        return "redirect:/buildReport/${rebuiltSubmission.id}";
    }

    /**
     * Controller that handles requests for marking a [Submission] as "final". Since, by design, students' can make
     * multiple submissions in DP, marking a submission as "final" is the way for the teacher to indicate that it's the
     * one that shall be considered when exporting the submissions' data (e.g. for grading purposes).
     *
     * Note that only one submission per [ProjectGroup] can be marked as final. This means that, when a certain submission
     * is marked as final, any previously "finalized" submission by the same group will not be final anymore.
     *
     * @param submissionId is a Long, identifying the student's Submission
     * @param redirectToSubmissionsList is a Boolean. If true, then after the marking process is done, the user will be
     * redirected to the group's submissions list. Otherwise, the redirection will be done to the the final submission's
     * build report.
     * @param principal is a [Principal] representing the user making the request
     *
     * @return a String identifying the relevant View
     */
    @RequestMapping(value = ["/markAsFinal/{submissionId}"], method = [(RequestMethod.POST)])
    fun markAsFinal(@PathVariable submissionId: Long,
                    @RequestParam(name="redirectToSubmissionsList", required = false, defaultValue = "false")
                    redirectToSubmissionsList: Boolean,
                    principal: Principal) : String {

        val submission = submissionRepository.findById(submissionId).orElse(null)
        val assignment = assignmentRepository.findById(submission.assignmentId).orElse(null)

        val acl = assignmentACLRepository.findByAssignmentId(assignment.id)
        if (principal.realName() != assignment.ownerUserId && acl.find { it -> it.userId == principal.realName() } == null) {
            throw IllegalAccessError("Submissions can only be marked as final by the assignment owner or authorized teachers")
        }

        if (submission.markedAsFinal) {
            submission.markedAsFinal = false
            LOG.info("Unmarking as final: ${submissionId}")

        } else {
            LOG.info("Marking as final: ${submissionId}")
            // marks this submission as final, and all other submissions for the same group and assignment as not final
            submissionService.markAsFinal(submission)
        }

        submissionRepository.save(submission)

        if (redirectToSubmissionsList) {
            return "redirect:/submissions/?assignmentId=${submission.assignmentId}&groupId=${submission.group.id}"
        } else {
            return "redirect:/buildReport/${submissionId}"
        }
    }

    // TODO: This should remove non-final submissions for groups where there is already a submission marked as final
    // removes all files related to non-final submissions
    @RequestMapping(value = ["/cleanup/{assignmentId}"], method = [(RequestMethod.POST)])
    fun cleanup(@PathVariable assignmentId: String, request: HttpServletRequest) : String {

        if (!request.isUserInRole("DROP_PROJECT_ADMIN")) {
            throw IllegalAccessError("Assignment can only be cleaned-up by admin")
        }

        LOG.info("Removing all non-final submission files related to ${assignmentId}")

        val nonFinalSubmissions = submissionRepository.findByAssignmentIdAndMarkedAsFinal(assignmentId, false)

        for (submission in nonFinalSubmissions) {
            val mavenizedProjectFolder = assignmentTeacherFiles.getProjectFolderAsFile(submission,
                    submission.getStatus() == SubmissionStatus.VALIDATED_REBUILT)
            if (mavenizedProjectFolder.deleteRecursively()) {
                LOG.info("Removed mavenized project folder (${submission.submissionId}): ${mavenizedProjectFolder}")
            } else {
                LOG.info("Error removing mavenized project folder (${submission.submissionId}): ${mavenizedProjectFolder}")
            }
        }

        // TODO: Should show a toast saying how many files were deleted
        return "redirect:/report/${assignmentId}";
    }

    /**
     * Controller that handles requests for connecting a student's GitHub repository with an [Assignment] available in DP.
     *
     * This process works like a two step wizard. This is the first part. The second part is in "/student/setup-git-2".
     *
     * @param assignmentId is a String, identifying the relevant Assignment
     * @param gitRepositoryUrl is a String with the student's GitHub repository URL
     * @param model is a [ModelMap] that will be populated with the information to use in a View
     * @param principal is a [Principal] representing the user making the request
     *
     * @return A String identifying the relevant View
     */
    @RequestMapping(value = ["/student/setup-git"], method = [(RequestMethod.POST)])
    fun setupStudentSubmissionUsingGitRepository(@RequestParam("assignmentId") assignmentId: String,
                                                 @RequestParam("gitRepositoryUrl") gitRepositoryUrl: String?,
                                                 model: ModelMap, principal: Principal,
                                                 request: HttpServletRequest): String {

        val assignment = assignmentRepository.getById(assignmentId)

        if (!request.isUserInRole("TEACHER")) {

            if (!assignment.active) {
                throw org.springframework.security.access.AccessDeniedException("Submissions are not open to this assignment")
            }

            checkAssignees(assignmentId, principal.realName())
        }

        model["assignment"] = assignment
        model["numSubmissions"] = submissionRepository.countBySubmitterUserIdAndAssignmentId(principal.realName(), assignment.id)
        model["instructionsFragment"] = assignmentTeacherFiles.getHtmlInstructionsFragment(assignment)
        model["packageTree"] = assignmentTeacherFiles.buildPackageTree(
                assignment.packageName, assignment.language, assignment.acceptsStudentTests)

        if (gitRepositoryUrl.isNullOrBlank()) {
            model["gitRepoErrorMsg"] = "Tens que preencher o endereço do repositório"
            return "student-git-form"
        }

        if (!gitClient.checkValidSSHGithubURL(gitRepositoryUrl)) {
            model["gitRepoErrorMsg"] = "O endereço do repositório não está no formato correcto"
            return "student-git-form"
        }

        var gitSubmission =
                gitSubmissionRepository.findBySubmitterUserIdAndAssignmentId(principal.realName(), assignmentId)
                ?:
                gitSubmissionService.findGitSubmissionBy(principal.realName(), assignmentId) // check if it belongs to a group who has already a git submission

        if (gitSubmission == null || gitSubmission.gitRepositoryPubKey == null) {

            // generate key pair
            val (privKey, pubKey) = gitClient.generateKeyPair()

            gitSubmission = GitSubmission(assignmentId = assignmentId,
                    submitterUserId = principal.realName(), gitRepositoryUrl = gitRepositoryUrl)
            gitSubmission.gitRepositoryPrivKey = String(privKey)
            gitSubmission.gitRepositoryPubKey = String(pubKey)
            gitSubmissionRepository.save(gitSubmission)
        }

        if (gitSubmission.gitRepositoryUrl.orEmpty().contains("github")) {
            val (username, reponame) = gitClient.getGitRepoInfo(gitSubmission.gitRepositoryUrl)
            model["repositorySettingsUrl"] = "https://github.com/${username}/${reponame}/settings/keys"
        }

        model["gitSubmission"] = gitSubmission

        return "student-setup-git"
    }

    /**
     * Controller that handles requests for connecting a student's GitHub repository with DP.
     *
     * This process works like a two step wizard. This is the second part. The first part is in "/student/setup-git".
     *
     * @param assignmentId
     * @param gitRepositoryUrl is a String
     * @param model is a [ModelMap] that will be populated with the information to use in a View
     * @param principal is a [Principal] representing the user making the request
     *
     * @return A String identifying the relevant View
     */
    @RequestMapping(value = ["/student/setup-git-2/{gitSubmissionId}"], method = [(RequestMethod.POST)])
    fun connectAssignmentToGitRepository(@PathVariable gitSubmissionId: String, redirectAttributes: RedirectAttributes,
                                         model: ModelMap, principal: Principal): String {

        val gitSubmission = gitSubmissionRepository.getById(gitSubmissionId.toLong())
        val assignment = assignmentRepository.getById(gitSubmission.assignmentId)

        if (!gitSubmission.connected) {

            run {
                val submissionFolder = File(gitSubmissionsRootLocation, gitSubmission.getFolderRelativeToStorageRoot())
                if (submissionFolder.exists()) {
                    submissionFolder.deleteRecursively()
                }
            }

            val gitRepository = gitSubmission.gitRepositoryUrl
            try {
                val projectFolder = File(gitSubmissionsRootLocation, gitSubmission.getFolderRelativeToStorageRoot())
                val git = gitClient.clone(gitRepository, projectFolder, gitSubmission.gitRepositoryPrivKey!!.toByteArray())
                LOG.info("[${gitSubmission}] Successfuly cloned ${gitRepository} to ${projectFolder}")

                // check that exists an AUTHORS.txt
                val authors = getProjectAuthors(projectFolder)
                LOG.info("[${authors.joinToString(separator = "|")}] Connected DP to ${gitSubmission.gitRepositoryUrl}")

                // check if the principal is one of group elements
                if (authors.filter { it.number == principal.realName() }.isEmpty()) {
                    throw InvalidProjectStructureException("O utilizador que está a submeter tem que ser um dos elementos do grupo.")
                }

                val group = projectGroupService.getOrCreateProjectGroup(authors)
                gitSubmission.group = group

                val lastCommitInfo = gitClient.getLastCommitInfo(git)
                gitSubmission.lastCommitDate = lastCommitInfo?.date
                gitSubmission.connected = true
                gitSubmissionRepository.save(gitSubmission)

                // let's check if the other students of this group have "pending" gitsubmissions, i.e., gitsubmissions that are half-way
                // in that case, delete that submissions, since this one will now be the official for all the elements in the group
                for (student in authors) {
                    if (student.number != gitSubmission.submitterUserId) {
                        val gitSubmissionForOtherStudent =
                                gitSubmissionRepository.findBySubmitterUserIdAndAssignmentId(student.number, gitSubmission.assignmentId)

                        if (gitSubmissionForOtherStudent != null) {
                            if (gitSubmissionForOtherStudent.connected) {
                                throw IllegalArgumentException("One of the elements of the group had already a connected git submission")
                            } else {
                                gitSubmissionRepository.delete(gitSubmissionForOtherStudent)
                            }
                        }
                    }
                }

            } catch (ipse: InvalidProjectStructureException) {
                LOG.info("Invalid project structure: ${ipse.message}")
                model["error"] = "O projecto localizado no repositório ${gitRepository} tem uma estrutura inválida: ${ipse.message}"
                model["gitSubmission"] = gitSubmission
                return "student-setup-git"
            } catch (e: Exception) {
                LOG.info("Error cloning ${gitRepository} - ${e} - ${e.cause}")
                model["error"] = "Error cloning ${gitRepository} - ${e.message}"
                model["gitSubmission"] = gitSubmission
                return "student-setup-git"
            }
        }

        redirectAttributes.addFlashAttribute("message", "Ligado com sucesso ao repositório git")
        return "redirect:/upload/${assignment.id}"
    }

    /**
     * Controller that handles requests for the creation of a new submission via Git, by refreshing the contents
     * that are in the respective student's repository.
     *
     * @param submissionId is a String identifying the student's last Git submission
     * @param principal is a [Principal] representing the user making the requets
     *
     * @return a ResponseEntity<String>
     */
    @RequestMapping(value = ["/git-submission/refresh-git/{submissionId}"], method = [(RequestMethod.POST)])
    fun refreshAssignmentGitRepository(@PathVariable submissionId: String,
                                       principal: Principal): ResponseEntity<String> {

        // check that it exists
        val gitSubmission = gitSubmissionRepository.getById(submissionId.toLong())

        if (!gitSubmission.group.contains(principal.realName())) {
            throw IllegalAccessError("Submissions can only be refreshed by their owners")
        }

        try {
            LOG.info("Pulling git repository for ${submissionId}")
            val git = gitClient.pull(File(gitSubmissionsRootLocation, gitSubmission.getFolderRelativeToStorageRoot()),
                    gitSubmission.gitRepositoryPrivKey!!.toByteArray())
            val lastCommitInfo = gitClient.getLastCommitInfo(git)

            if (lastCommitInfo?.date != gitSubmission.lastCommitDate) {
                gitSubmission.lastSubmissionId = null
                gitSubmissionRepository.save(gitSubmission)
            }

        } catch (re: RefNotAdvertisedException) {
            LOG.warn("Couldn't pull git repository for ${submissionId}: head is invalid")
            return ResponseEntity("{ \"error\": \"Error pulling from ${gitSubmission.gitRepositoryUrl}. Probably you don't have any commits yet.\"}", HttpStatus.INTERNAL_SERVER_ERROR)
        } catch (e: Exception) {
            LOG.warn("Couldn't pull git repository for ${submissionId}")
            return ResponseEntity("{ \"error\": \"Error pulling from ${gitSubmission.gitRepositoryUrl}\"}", HttpStatus.INTERNAL_SERVER_ERROR)
        }

        return ResponseEntity("{ \"success\": \"true\"}", HttpStatus.OK);
    }

    /**
     * Controller that handles requests for the generation of a [GitSubmission]'s build report.
     *
     * @param gitSubmissionId is a String identifying a [GitSubmission]
     * @param principal is a [Principal] representing the user making the request
     * @param request is a HttpServletRequest
     *
     * @return a ResponseEntity<String>
     */
    @RequestMapping(value = ["/git-submission/generate-report/{gitSubmissionId}"], method = [(RequestMethod.POST)])
    fun upload(@PathVariable gitSubmissionId: String,
               principal: Principal,
               request: HttpServletRequest): ResponseEntity<String> {

        val gitSubmission = gitSubmissionRepository.findById(gitSubmissionId.toLong()).orElse(null) ?:
        throw IllegalArgumentException("git submission ${gitSubmissionId} is not registered")
        val assignment = assignmentRepository.findById(gitSubmission.assignmentId).orElse(null)

        // TODO: Validate assignment due date

        if (!request.isUserInRole("TEACHER")) {

            if (!assignment.active) {
                throw org.springframework.security.access.AccessDeniedException("Submissions are not open to this assignment")
            }

            checkAssignees(assignment.id, principal.realName())
        }

        if (assignment.cooloffPeriod != null) {
            val lastSubmission = getLastSubmission(principal, assignment.id)
            if (lastSubmission != null) {
                val nextSubmissionTime = calculateCoolOff(lastSubmission, assignment)
                if (nextSubmissionTime != null) {
                    LOG.warn("[${principal.realName()}] can't submit because he is in cool-off period")
                    throw org.springframework.security.access.AccessDeniedException("[${principal.realName()}] can't submit because he is in cool-off period")
                }
            }
        }

        val projectFolder = File(gitSubmissionsRootLocation, gitSubmission.getFolderRelativeToStorageRoot())

        // verify that there is not another submission with the Submitted status
        val existingSubmissions = submissionRepository
                .findByGroupAndAssignmentIdOrderBySubmissionDateDescStatusDateDesc(gitSubmission.group, assignment.id)
        for (submission in existingSubmissions) {
            if (submission.getStatus() == SubmissionStatus.SUBMITTED) {
                LOG.info("[${gitSubmission.group.authorsNameStr()}] tried to submit before the previous one has been validated")
                return ResponseEntity("{\"error\": \"A submissão anterior ainda não foi validada. Aguarde pela geração do relatório para voltar a submeter.\"}", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        val submission = Submission(gitSubmissionId = gitSubmission.id, submissionDate = Date(),
                status = SubmissionStatus.SUBMITTED.code, statusDate = Date(), assignmentId = assignment.id,
                submitterUserId = principal.realName())
        submission.group = gitSubmission.group
        submissionRepository.save(submission)

        buildSubmission(projectFolder, assignment, gitSubmission.group.authorsStr("|"), submission, asyncExecutor, principal = principal)

        return ResponseEntity("{ \"submissionId\": \"${submission.id}\"}", HttpStatus.OK);

    }

    /**
     * Controller that handles requests for the resetting of the connection between a GitHub and an Assignment.
     *
     * @param gitSubmissionId is a String identifying a [GitSubmission]
     * @param redirectAttributes is a RedirectAttributes
     * @param principal is [Principal] representing the user making the request
     *
     * @return a String with the name of the relevant View
     */
    @RequestMapping(value = ["/student/reset-git/{gitSubmissionId}"], method = [(RequestMethod.POST)])
    fun disconnectAssignmentToGitRepository(@PathVariable gitSubmissionId: String,
                                            redirectAttributes: RedirectAttributes,
                                            principal: Principal): String {

        LOG.info("[${principal.realName()}] Reset git connection")

        val gitSubmission = gitSubmissionRepository.getById(gitSubmissionId.toLong())
        val assignment = assignmentRepository.getById(gitSubmission.assignmentId)
        val repositoryUrl = gitSubmission.gitRepositoryUrl

        if (!principal.realName().equals(gitSubmission.submitterUserId)) {
            redirectAttributes.addFlashAttribute("error", "Apenas o utilizador que fez a ligação (${gitSubmission.submitterUserId}) é que pode remover a ligação")
            return "redirect:/upload/${assignment.id}"
        }

        if (gitSubmission.connected) {
            val submissionFolder = File(gitSubmissionsRootLocation, gitSubmission.getFolderRelativeToStorageRoot())
            if (submissionFolder.exists()) {
                LOG.info("[${principal.realName()}] Removing ${submissionFolder.absolutePath}")
                submissionFolder.deleteRecursively()
            }
        }

        gitSubmissionService.deleteGitSubmission(gitSubmission)
        LOG.info("[${principal.realName()}] Removed submission from the DB")

        redirectAttributes.addFlashAttribute("message", "Desligado com sucesso do repositório ${repositoryUrl}")
        return "redirect:/upload/${assignment.id}"
    }

    /**
     * Controller that handles requests for the deletion of a [Submission].
     *
     * @param submissionId is a Long, identifying the Submission to delete
     * @param principal is a [Principal] representing the user making the request
     * @return a String with the name of the relevant View
     */
    @RequestMapping(value = ["/delete/{submissionId}"], method = [(RequestMethod.POST)])
    fun deleteSubmission(@PathVariable submissionId: Long,
                         principal: Principal) : String {

        val submission = submissionRepository.findById(submissionId).orElse(null)
        val assignment = assignmentRepository.findById(submission.assignmentId).orElse(null)

        val acl = assignmentACLRepository.findByAssignmentId(assignment.id)
        if (principal.realName() != assignment.ownerUserId && acl.find { it -> it.userId == principal.realName() } == null) {
            throw IllegalAccessError("Submissions can only be deleted by the assignment owner or authorized teachers")
        }

        submission.setStatus(SubmissionStatus.DELETED)
        submissionRepository.save(submission)

        LOG.info("[${principal.realName()}] deleted submission $submissionId")

        return "redirect:/report/${assignment.id}"
    }

    /**
     * Checks if a certain user can access a certain [Assignment]. Only relevant for Assignments that have access
     * control lists.
     *
     * @param assignmentId is a String identifying the relevant Assignment
     * @param principalName is a String identifyng the user trying to access the Assignment
     * @throws If the user is not allowed to access the Assignment, an [AccessDeniedException] will be thrown.
     */
    private fun checkAssignees(assignmentId: String, principalName: String) {
        if (assigneeRepository.existsByAssignmentId(assignmentId)) {
            // if it enters here, it means this assignment has a white list
            // let's check if the current user belongs to the white list
            if (!assigneeRepository.existsByAssignmentIdAndAuthorUserId(assignmentId, principalName)) {
                throw AccessDeniedException("${principalName} is not allowed to view this assignment")
            }
        }
    }

    /**
     * Searches for the last [Submission] performed in an [Assignment] by a certain user or by a member of the respective
     * [ProjectGroup].
     *
     * @param principal is a [Principal] representing the user whose last submission is being searched
     * @param assignmentId is a String representing the relevant Assignment
     *
     * @return a Submission
     */
    private fun getLastSubmission(principal: Principal, assignmentId: String): Submission? {
        val groupsToWhichThisStudentBelongs = projectGroupRepository.getGroupsForAuthor(principal.realName())
        var lastSubmission: Submission? = null
        // TODO: This is ugly - should rethink data model for groups
        for (group in groupsToWhichThisStudentBelongs) {
            val lastSubmissionForThisGroup = submissionRepository
                    .findFirstByGroupAndAssignmentIdOrderBySubmissionDateDescStatusDateDesc(group, assignmentId)
            if (lastSubmission == null ||
                    (lastSubmissionForThisGroup != null &&
                            lastSubmission.submissionDate.before(lastSubmissionForThisGroup.submissionDate))) {
                lastSubmission = lastSubmissionForThisGroup
            }
        }
        return lastSubmission
    }

    // returns the date when the next submission can be made or null if it's not in cool-off period
    private fun calculateCoolOff(lastSubmission: Submission, assignment: Assignment) : LocalDateTime? {
        val lastSubmissionDate = Timestamp(lastSubmission.submissionDate.time).toLocalDateTime()
        val now = LocalDateTime.now()
        val delta = ChronoUnit.MINUTES.between(lastSubmissionDate, now)

        val reportElements = submissionReportRepository.findBySubmissionId(lastSubmission.id)
        val cooloffPeriod =
            if (reportElements.any {
                        (it.reportValue == "NOK" &&
                                (it.reportKey == Indicator.PROJECT_STRUCTURE.code ||
                                        it.reportKey == Indicator.COMPILATION.code)) } ) {
                Math.min(Constants.COOLOFF_FOR_STRUCTURE_OR_COMPILATION, assignment.cooloffPeriod!!)
            } else {
                assignment.cooloffPeriod!!
            }

        if (delta < cooloffPeriod) {
            return lastSubmissionDate.plusMinutes(cooloffPeriod.toLong())
        }

        return null
    }

    @Throws(IOException::class)
    private fun guessCharset(inputStream: InputStream): Charset {
        try {
            return Charset.forName(TikaEncodingDetector().guessEncoding(inputStream))
        } catch (e: UnsupportedCharsetException) {
            LOG.warn("Unsupported Charset: ${e.charsetName}. Falling back to default")
            return Charset.defaultCharset()
        }
    }

    @ExceptionHandler(StorageException::class)
    fun handleStorageError(e: StorageException): ResponseEntity<String> {
        LOG.error(e.message)
        return ResponseEntity("{\"error\": \"Falha a gravar ficheiro => ${e.message}\"}", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(InvalidProjectStructureException::class)
    fun handleStorageError(e: InvalidProjectStructureException): ResponseEntity<String> {
        LOG.warn(e.message)
        return ResponseEntity("{\"error\": \"${e.message}\"}", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Inexistent assignment")
    class AssignmentNotFoundException(assignmentId: String) : Exception("Inexistent assignment ${assignmentId}") {
        val LOG = LoggerFactory.getLogger(this.javaClass.name)

        init {
            LOG.warn("Inexistent assignment ${assignmentId}")
        }
    }

    @ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Inexistent submission")
    class SubmissionNotFoundException(submissionId: Long) : Exception("Inexistent submission ${submissionId}") {
        val LOG = LoggerFactory.getLogger(this.javaClass.name)

        init {
            LOG.warn("Inexistent submission ${submissionId}")
        }
    }

}
