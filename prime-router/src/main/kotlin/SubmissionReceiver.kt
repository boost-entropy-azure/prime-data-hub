package gov.cdc.prime.router

import ca.uhn.hl7v2.model.Message
import gov.cdc.prime.router.Report.Format
import gov.cdc.prime.router.azure.ActionHistory
import gov.cdc.prime.router.azure.BlobAccess
import gov.cdc.prime.router.azure.Event
import gov.cdc.prime.router.azure.ProcessEvent
import gov.cdc.prime.router.azure.ReportWriter
import gov.cdc.prime.router.azure.WorkflowEngine
import gov.cdc.prime.router.azure.db.enums.TaskAction
import gov.cdc.prime.router.fhirengine.engine.RawSubmission
import gov.cdc.prime.router.fhirengine.engine.elrConvertQueueName
import gov.cdc.prime.router.fhirengine.utils.FhirTranscoder
import gov.cdc.prime.router.fhirengine.utils.HL7Reader
import ca.uhn.hl7v2.model.v251.segment.MSH as v251_MSH
import ca.uhn.hl7v2.model.v27.segment.MSH as v27_MSH

/**
 * The base class for a 'receiver' type, currently just for COVID or full ELR submissions. This allows us a fan out
 * point to provide for different pipeline pathways for different submissions types.
 *  [workflowEngine] is the workflow engine to use. Mocked engine is passed in for testing
 *  [actionHistory] is the action history instance to use. Mocked actionHistory is passed in for testing
 */
abstract class SubmissionReceiver(
    internal val workflowEngine: WorkflowEngine = WorkflowEngine(),
    internal val actionHistory: ActionHistory = ActionHistory(TaskAction.receive)
) {
    /**
     * The main function that will be called from ReportFunction. Encapsulates all logic needed to validate
     * and send a submission to the next step in the pipeline, or return error messages to the user.
     *
     * [sender] the sender of the submission
     * [content] string representation of the submission
     * [defaults] defaults to apply
     * [options] Options for processing, if any
     * [routeTo] override for routing
     * [isAsync] true if this will be processed async. ELR senders must have this set to true
     * [allowDuplicates] If true will detect duplicates and return errors if there are any
     * [rawBody] the byteArray representing the incoming submission
     * [payloadName] optional sender-determined name of the payload
     * [metadata] mockable metadata to use in report creation
     */
    abstract fun validateAndMoveToProcessing(
        sender: Sender,
        content: String,
        defaults: Map<String, String>,
        options: Options,
        routeTo: List<String>,
        isAsync: Boolean,
        allowDuplicates: Boolean,
        rawBody: ByteArray,
        payloadName: String?,
        metadata: Metadata? = null
    )

    companion object {
        /**
         * Checks the [report] rows looking for duplicates, and adds errors as needed
         */
        internal fun doDuplicateDetection(
            workflowEngine: WorkflowEngine,
            report: Report,
            actionLogs: ActionLogger
        ) {
            // keep list of hashes generated by this ingest to do same-file comparison
            val generatedHashes = mutableListOf<String>()
            val duplicateIndexes = mutableListOf<Int>()
            for (rowNum in 0 until report.itemCount) {
                val itemHash = report.getItemHashForRow(rowNum)
                // check for duplicate item
                val isDuplicate = generatedHashes.contains(itemHash) ||
                    workflowEngine.isDuplicateItem(itemHash)
                if (isDuplicate) {
                    duplicateIndexes.add(rowNum + 1)
                } else {
                    generatedHashes.add(itemHash)
                }
            }
            // TODO Tech Debt: We need a more consistent way to handle 'trackingId'. Once we have one, we can
            //  pass it in here to ensure a trackingId is returned in the response message
            // sanity check - if all items are duplicate
            if (duplicateIndexes.size == report.itemCount) {
                addDuplicateLogs(actionLogs, null, null, null)
            }
            // add logs as needed
            else {
                duplicateIndexes.forEach {
                    addDuplicateLogs(actionLogs, null, it, null)
                }
            }
        }

        /**
         * If a [rowNum] is passed, a DuplicateItemMessage with [message] is added to [actionLogs] as an error.
         * If no [rowNum] is passed, a DuplicateFileMessage with [message] is added to [actionLogs] as an error.
         * If a [trackingId] is passed, it will be used in the response message
         * [actionLogs] is used to get the logger that duplicate messages are sent to
         * [payloadName] is the optional parameter passed by the client that tracks what the client calls the file
         */
        internal fun addDuplicateLogs(
            actionLogs: ActionLogger,
            payloadName: String?,
            rowNum: Int?,
            trackingId: String?
        ) {
            if (rowNum != null) {
                actionLogs.getItemLogger(rowNum, trackingId).error(DuplicateItemMessage())
            } else {
                // duplicate files are always an error, never a warning
                actionLogs.error(DuplicateSubmissionMessage(payloadName))
            }
        }

        /**
         * Determines what type of submission receiver to use based on [sender]
         * Creates a new SubmissionReceiver using the given [workflowEngine] and [actionHistory]
         * @return Returns either a TopicReceiver or ELRReceiver based on the sender
         */
        internal fun getSubmissionReceiver(
            sender: Sender,
            workflowEngine: WorkflowEngine,
            actionHistory: ActionHistory
        ): SubmissionReceiver {
            val receiver by lazy {
                when (sender) {
                    is CovidSender, is MonkeypoxSender -> TopicReceiver(workflowEngine, actionHistory)
                    else -> UniversalPipelineReceiver(workflowEngine, actionHistory)
                }
            }
            return receiver
        }
    }
}

/**
 * Receiver for submissions with a specific topic, contains all logic to parse and move
 * a topic'd submission to the next step in the pipeline
 */
class TopicReceiver : SubmissionReceiver {
    constructor(
        workflowEngine: WorkflowEngine = WorkflowEngine(),
        actionHistory: ActionHistory = ActionHistory(TaskAction.receive)
    ) : super(workflowEngine, actionHistory)

    override fun validateAndMoveToProcessing(
        sender: Sender,
        content: String,
        defaults: Map<String, String>,
        options: Options,
        routeTo: List<String>,
        isAsync: Boolean,
        allowDuplicates: Boolean,
        rawBody: ByteArray,
        payloadName: String?,
        metadata: Metadata?
    ) {
        // parse, check for parse errors
        val (report, actionLogs) = this.workflowEngine.parseTopicReport(
            sender as LegacyPipelineSender,
            content,
            defaults
        )

        // prevent duplicates if configured to not allow them
        if (!allowDuplicates) {
            doDuplicateDetection(
                workflowEngine,
                report,
                actionLogs
            )
        }

        // if there are any errors, kick this out.
        if (actionLogs.hasErrors()) {
            throw actionLogs.exception
        }

        workflowEngine.recordReceivedReport(
            report,
            rawBody,
            sender,
            actionHistory,
            payloadName
        )

        actionHistory.trackLogs(actionLogs.logs)

        // send to next step
        // call the correct processing function based on processing type
        if (isAsync) {
            processAsync(
                report,
                options,
                defaults,
                routeTo
            )
        } else {
            val routingWarnings = workflowEngine.routeReport(
                report,
                options,
                defaults,
                routeTo,
                actionHistory
            )
            actionHistory.trackLogs(routingWarnings)
        }
    }

    /**
     *  Processes a non-ELR report into the async pipeline
     * [parsedReport] The report to process
     * [options] Processing options, if any
     * [defaults] Defaults for missing values, if any
     * [routeTo] Override routeTo value, if any
     */
    internal fun processAsync(
        parsedReport: Report,
        options: Options,
        defaults: Map<String, String>,
        routeTo: List<String>
    ) {
        val report = parsedReport.copy()
        val senderSource = parsedReport.sources.firstOrNull()
            ?: error("Unable to process report ${report.id} because sender sources collection is empty.")
        val senderName = (senderSource as ClientSource).name

        if (report.bodyFormat != Format.INTERNAL) {
            error("Processing a non internal report async.")
        }

        val processEvent = ProcessEvent(Event.EventAction.PROCESS, report.id, options, defaults, routeTo)

        val bodyBytes = ReportWriter.getBodyBytes(report)
        val blobInfo = workflowEngine.blob.uploadReport(report, bodyBytes, senderName, processEvent.eventAction)
        actionHistory.trackCreatedReport(processEvent, report, blobInfo)

        // add task to task table
        workflowEngine.insertProcessTask(report, report.bodyFormat.toString(), blobInfo.blobUrl, processEvent)
    }
}

/**
 * Receiver for Universal Pipeline, contains logic to process Universal Pipeline submissions.
 */
class UniversalPipelineReceiver : SubmissionReceiver {
    constructor(
        workflowEngine: WorkflowEngine = WorkflowEngine(),
        actionHistory: ActionHistory = ActionHistory(TaskAction.receive)
    ) : super(workflowEngine, actionHistory)

    override fun validateAndMoveToProcessing(
        sender: Sender,
        content: String,
        defaults: Map<String, String>,
        options: Options,
        routeTo: List<String>,
        isAsync: Boolean,
        allowDuplicates: Boolean,
        rawBody: ByteArray,
        payloadName: String?,
        metadata: Metadata?
    ) {
        check(sender is UniversalPipelineSender)
        val actionLogs = ActionLogger()
        val sources = listOf(ClientSource(organization = sender.organizationName, client = sender.name))
        // check that our input is valid HL7. Additional validation will happen at a later step

        val report: Report

        when (sender.format) {
            Sender.Format.HL7 -> {
                val messages = HL7Reader(actionLogs).getMessages(content)
                val isBatch = HL7Reader(actionLogs).isBatch(content, messages.size)
                // create a Report for this incoming HL7 message to use for tracking in the database

                report = Report(
                    if (isBatch) Format.HL7_BATCH else Format.HL7,
                    sources,
                    messages.size,
                    metadata = metadata,
                    nextAction = TaskAction.convert,
                    topic = sender.topic,
                )

                // dupe detection if needed, and if we have not already produced an error
                if (!allowDuplicates && !actionLogs.hasErrors()) {
                    doDuplicateDetection(
                        workflowEngine,
                        report,
                        actionLogs
                    )
                }

                // check for valid message type
                messages.forEachIndexed { idx, element -> checkValidMessageType(element, actionLogs, idx + 1) }
            }

            Sender.Format.FHIR -> {
                val bundles = FhirTranscoder.getBundles(content, actionLogs)
                report = Report(
                    Format.FHIR,
                    sources,
                    bundles.size,
                    metadata = metadata,
                    nextAction = TaskAction.convert,
                    topic = sender.topic,
                )
            }

            else -> {
                throw IllegalStateException("Unexpected sender format ${sender.format}")
            }
        }

        // if there are any errors, kick this out.
        if (actionLogs.hasErrors()) {
            throw actionLogs.exception
        }

        // If the sender is disabled, there should be no next event
        // THIS MUST HAPPEN BEFORE workflow.recordReceivedReport so that the next action
        // is properly stored in the report in the DB
        val eventAction = if (sender.customerStatus == CustomerStatus.INACTIVE) {
            report.nextAction = TaskAction.none
            Event.EventAction.NONE
        } else Event.EventAction.CONVERT

        // record that the submission was received
        val blobInfo = workflowEngine.recordReceivedReport(
            report,
            rawBody,
            sender,
            actionHistory,
            payloadName
        )

        // track logs
        actionHistory.trackLogs(actionLogs.logs)

        // add task to task table
        val processEvent = ProcessEvent(eventAction, report.id, options, defaults, routeTo)
        workflowEngine.insertProcessTask(report, report.bodyFormat.toString(), blobInfo.blobUrl, processEvent)

        // Only add to queue if the sender/ is enabled
        if (sender.customerStatus != CustomerStatus.INACTIVE) {
            // move to processing (send to <elrProcessQueueName> queue)
            workflowEngine.queue.sendMessage(
                elrConvertQueueName,
                RawSubmission(
                    report.id,
                    blobInfo.blobUrl,
                    BlobAccess.digestToString(blobInfo.digest),
                    sender.fullName,
                    sender.topic,
                    sender.schemaName,
                ).serialize()
            )
        }
    }

    enum class MessageType {
        ORU_R01,
        ORM_O01
    }

    /**
     * Checks that a [message] is of the supported type(s), and uses the [actionLogs] to add an error
     * message for item with index [itemIndex] if it is not.
     */
    internal fun checkValidMessageType(message: Message, actionLogs: ActionLogger, itemIndex: Int) {
        val messageType = when (val msh = message.get("MSH")) {
            is v251_MSH -> msh.messageType.messageStructure.toString()
            is v27_MSH -> msh.messageType.messageStructure.toString()
            else -> ""
        }

        if (!MessageType.values().map { it.toString() }.contains(messageType)) {
            actionLogs.getItemLogger(itemIndex)
                .error(InvalidHL7Message("Ignoring unsupported HL7 message type $messageType"))
        }
    }
}