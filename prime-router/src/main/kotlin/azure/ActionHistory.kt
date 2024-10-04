package gov.cdc.prime.router.azure

import com.fasterxml.jackson.annotation.JsonProperty
import com.microsoft.azure.functions.HttpMethod
import com.microsoft.azure.functions.HttpRequestMessage
import com.microsoft.azure.functions.HttpResponseMessage
import com.microsoft.azure.functions.HttpStatusType
import com.networknt.org.apache.commons.validator.routines.InetAddressValidator
import fhirengine.engine.CustomFhirPathFunctions
import gov.cdc.prime.reportstream.shared.BlobUtils
import gov.cdc.prime.router.ActionLog
import gov.cdc.prime.router.ActionLogLevel
import gov.cdc.prime.router.ClientSource
import gov.cdc.prime.router.MimeFormat
import gov.cdc.prime.router.Receiver
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.ReportId
import gov.cdc.prime.router.Sender
import gov.cdc.prime.router.Topic
import gov.cdc.prime.router.azure.ActionHistory.ReceivedReportSenderParameters.Companion.removeExcludedParameters
import gov.cdc.prime.router.azure.db.Tables.ACTION
import gov.cdc.prime.router.azure.db.enums.TaskAction
import gov.cdc.prime.router.azure.db.tables.pojos.Action
import gov.cdc.prime.router.azure.db.tables.pojos.CovidResultMetadata
import gov.cdc.prime.router.azure.db.tables.pojos.ElrResultMetadata
import gov.cdc.prime.router.azure.db.tables.pojos.ItemLineage
import gov.cdc.prime.router.azure.db.tables.pojos.ReportFile
import gov.cdc.prime.router.azure.db.tables.pojos.ReportLineage
import gov.cdc.prime.router.azure.db.tables.pojos.Task
import gov.cdc.prime.router.azure.observability.bundleDigest.BundleDigestExtractor
import gov.cdc.prime.router.azure.observability.bundleDigest.FhirPathBundleDigestLabResultExtractorStrategy
import gov.cdc.prime.router.azure.observability.event.IReportStreamEventService
import gov.cdc.prime.router.azure.observability.event.ReportStreamEventName
import gov.cdc.prime.router.azure.observability.event.ReportStreamEventProperties
import gov.cdc.prime.router.common.AzureHttpUtils.getSenderIP
import gov.cdc.prime.router.common.JacksonMapperUtilities
import gov.cdc.prime.router.fhirengine.translation.hl7.utils.CustomContext
import gov.cdc.prime.router.fhirengine.utils.FhirTranscoder
import gov.cdc.prime.router.report.ReportService
import io.ktor.http.HttpStatusCode
import org.apache.logging.log4j.kotlin.Logging
import org.jooq.impl.SQLDataType
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDateTime

/**
 * This is a container class that holds information to be stored, about a single action,
 * as well as the reports that went into that Action, and were created by that Action.
 *
 * The idea is that, as an action progresses, call various track*(...) methods here to add additional information to
 * this container, in-memory only.
 *
 * Then, when the action is done, call saveToDb(...) to plunk all the tracked information into the database.
 *
 */
class ActionHistory(
    taskAction: TaskAction,
    // This will be true if this actionHistory is being used to track generation of an empty batch file
    var generatingEmptyReport: Boolean = false,
) : Logging {
    /**
     * Throughout, using generated mutable jooq POJOs to store history information
     *
     */
    val action = Action()
    val startTime: LocalDateTime

    init {
        action.actionName = taskAction
        startTime = LocalDateTime.now()
    }

    /**
     * Reports that are inputs to this action, from previous steps.
     * These reports are already in report_file.  For this action, we insert them as parents into
     * report_lineage.
     */
    val reportsIn = mutableMapOf<ReportId, ReportFile>()

    /**
     * Reports that are inputs to this action, from external source.
     * Note that this should be able to handle multiple submitted reports in one action.
     * For this action, we insert these into report_file, and as parents into report_lineage.
     */
    val reportsReceived = mutableMapOf<ReportId, ReportFile>()

    /**
     * Stores covid metadata records that are created when a new report is received with the topic covid-19
     */
    // todo: genericize so we can save this information without having to store it here
    val elrMetaDataRecords = mutableListOf<ElrResultMetadata>()
    val covidResultMetadataRecords = mutableListOf<CovidResultMetadata>()

    /**
     * New reports generated by this action.
     * For this action, we insert these into report_file, and as children into report_lineage.
     */
    val reportsOut = mutableMapOf<ReportId, ReportFile>()

    /**
     * List of reports that have been completely filtered out based on quality.
     */
    val filteredOutReports = mutableMapOf<ReportId, ReportFile>()

    /**
     * A List of events describing the details of what has happened during an Action.
     */
    val actionLogs = mutableListOf<ActionLog>()

    /**
     * Messages to be queued in an azure queue as part of the result of this action.
     */
    val messages = mutableListOf<Event>()

    /**
     *
     * Collection of all the parent-child report relationships created by this action.
     *
     * Note:  There is a strong OO argument that this list should be broken out into each individual child Report.kt.
     * (That is, every report should know its own parents!)
     * However, its here because there are Functions that do not create Report.kt objects.  For example, Send.
     * In addition, in-memory, reports get copied many times, with lots of parent-child relationships
     * that are error-prone to track.  Hiding the lineage data here helps ensure correctness and hide complexity.
     */
    val reportLineages = mutableListOf<ReportLineage>()

    /**
     * Set of new parent->child Item mappings created by this Action.
     * Note this crucial assumption: the ordering of rows is fixed within any one report.
     */
    val itemLineages = mutableSetOf<ItemLineage>()

    fun setActionType(taskAction: TaskAction) {
        action.actionName = taskAction
    }

    /** Adds a queue event to the messages property to be added to the queue later */
    private fun trackEvent(event: Event) {
        messages.add(event)
    }

    /**
     * Track a list of logs that happened during this action
     *
     * @param logs The List of ActionLogs to track against this action
     */
    fun trackLogs(logs: List<ActionLog>) {
        logs.forEach {
            trackLogs(it)
        }
    }

    /**
     * Track a log that happened during this action
     *
     * @param log The ActionLogs to track against this action
     */
    fun trackLogs(log: ActionLog) {
        log.action = action
        actionLogs.add(log)
    }

    fun trackUsername(userName: String?) {
        action.username = userName
    }

    /**
     * Class captures data about the HTTP request from a sender to the report endpoint
     *
     */
    data class ReceivedReportSenderParameters(
        val method: HttpMethod,
        val url: String,
        @JsonProperty("Headers")
        val headers: Map<String, String>,
        @JsonProperty("QueryParameters")
        val queryParameters: Map<String, String>,
    ) {
        companion object {
            // These header/query params can potentially contain auth information, and we do not want to log them
            // gitleaks:allow
            private val parameterNamesToExclude = setOf(
                "key",
                "cookie",
                "auth", // gitleaks:allow
                "code" // gitleaks:allow
            )

            fun removeExcludedParameters(it: Map.Entry<String, String>) =
                !parameterNamesToExclude.any { excluded ->
                    it.key.contains(
                        excluded,
                        ignoreCase = true
                    )
                }
        }
    }

    fun filterParameters(request: HttpRequestMessage<String?>): ReceivedReportSenderParameters {
        val filteredHeaders = request.headers
            .filter {
                removeExcludedParameters(it)
            }

        val filteredQueryParams = request.queryParameters.filter {
            removeExcludedParameters(it)
        }
        return ReceivedReportSenderParameters(
            request.httpMethod,
            request.uri.toString(),
            filteredHeaders,
            filteredQueryParams
        )
    }

    /**
     * Track the parameters of a [request].
     */
    fun trackActionParams(request: HttpRequestMessage<String?>) {
        val params = filterParameters(request)

        action.contentLength = request.headers["content-length"]?.let {
            try {
                it.toInt()
            } catch (e: NumberFormatException) {
                null
            }
        }
        // capture the azure client IP but override with the first forwarded for if present
        val senderIp = getSenderIP(request)
        if (senderIp != null && InetAddressValidator.getInstance().isValid(senderIp)) {
            action.senderIp = senderIp
        }
        trackActionParams(JacksonMapperUtilities.objectMapper.writeValueAsString(params))
    }

    /**
     * Sets the [actionId] on the action and all internal reports after saving the action to the database
     */
    fun setActionId(actionId: Long) {
        action.actionId = actionId
        reportsReceived.values.forEach { it.actionId = action.actionId }
        reportsOut.values.forEach { it.actionId = action.actionId }
        filteredOutReports.values.forEach { it.actionId = action.actionId }
    }

    /**
     * Generate lineage data for insertion into database
     */
    fun generateLineages() {
        if (generatingEmptyReport) {
            // if we are generating an empty report for the 'send' step there will be one report in and one out.
            //  make sure to track the lineage. for the 'batch 'step there will not be any lineage
            if (reportsIn.size == 1 && reportsOut.size == 1) {
                reportLineages.add(
                    ReportLineage(
                        null,
                        action.actionId,
                        reportsIn.values.first().reportId,
                        reportsOut.values.first().reportId,
                        null
                    )
                )
            }
        } else {
            generateReportLineagesUsingItemLineage(action.actionId)
        }
    }

    /**
     * if we have an action log that is for a report that is not being recorded, remove the link to the report.
     * this is a valid use case when the client submission is incorrect - a report is created in memory but
     * is not stored in the database or blob store, but we will want the action log
     */
    fun nullifyReportIdsForNonTrackedReports() {
        actionLogs.forEach {
            if (it.reportId != null &&
                !(
                    reportsReceived.containsKey(it.reportId) ||
                        reportsOut.containsKey(it.reportId) ||
                        filteredOutReports.containsKey(it.reportId) ||
                        reportsIn.containsKey(it.reportId)
                    )
            ) {
                it.reportId = null
            }
        }
    }

    /**
     * Always appends, to allow for actions that do a mix of work (eg, SEND)
     */
    fun trackActionParams(actionParams: String) {
        if (actionParams.isEmpty()) return
        val tmp = if (action.actionParams.isNullOrBlank()) actionParams else "${action.actionParams}, $actionParams"
        // kluge to get the max size of the varchar
        val max = ACTION.ACTION_PARAMS.dataType.length()
        // truncate if needed
        action.actionParams = tmp.chunked(size = max)[0]
    }

    /**
     * Always appends
     */
    fun trackActionResult(actionResult: String) {
        val tmp = if (action.actionResult.isNullOrBlank()) actionResult else "${action.actionResult}, $actionResult"
        val max = ACTION.ACTION_RESULT.dataType.length()
        // max is 0 for the CLOB type. we're using CLOB for the action_result now because we want
        // bigly strings, not just small sad 2048 strings
        action.actionResult = if (ACTION.ACTION_RESULT.dataType == SQLDataType.CLOB && max == 0) {
            tmp
        } else {
            tmp.chunked(size = max)[0]
        }
    }

    /**
     * Track the response result of an action by using its [httpStatus] and [responseBody].
     */
    fun trackActionResult(httpStatus: HttpStatusType, responseBody: String? = null) {
        action.httpStatus = httpStatus.value()
        trackActionResult(
            httpStatus.toString() +
                if (responseBody != null) ": $responseBody" else ""
        )
    }

    /**
     * Track the response result of an action by using its [httpStatus] and a [msg].
     */
    fun trackActionResult(httpStatus: HttpStatusCode, msg: String? = null) {
        action.httpStatus = httpStatus.value
        trackActionResult(msg ?: "")
    }

    /**
     * Calls trackActionParams with [request] as param, and then trackActionResult with the status of the
     * [response] as param
     */
    fun trackActionRequestResponse(request: HttpRequestMessage<String?>, response: HttpResponseMessage) {
        trackActionParams(request)
        trackActionResult(response.status)
    }

    /**
     * Parses the client parameter and sets the sending organization
     * and client in the action table.
     * @param clientParam the client header submitted with the report
     * @param payloadName an optional user-supplied name for the data submitted.  Eg, a filename.
     */
    fun trackActionSenderInfo(clientParam: String, payloadName: String? = null) {
        // only set the action properties if not null
        if (clientParam.isNotBlank()) {
            try {
                val (sendingOrg, sendingOrgClient) = Sender.parseFullName(clientParam)
                action.sendingOrg = sendingOrg.take(ACTION.SENDING_ORG.dataType.length())
                action.sendingOrgClient = sendingOrgClient.take(ACTION.SENDING_ORG_CLIENT.dataType.length())
            } catch (e: Exception) {
                logger.warn(
                    "Exception tracking sender: ${e.localizedMessage} ${e.stackTraceToString()}"
                )
            }
        }
        action.externalName = payloadName
    }

    /**
     * Adds information to the Action object about the organization and receiver channel affected by this action.
     * Typically, this would be called when a report is batched for that receiver, sent to that receiver,
     * downloaded by that receiver, or any other action taken by that receiver or on behalf of that receiver.
     * @param organizationName  The name of the receiving organization to associate with this action.
     * @param receiverName  The name of the receiver channel to associate with this action.
     */
    fun trackActionReceiverInfo(organizationName: String, receiverName: String) {
        action.receivingOrg = organizationName
        action.receivingOrgSvc = receiverName
    }

    /**
     * Sanity check: No report can be tracked twice, either as an input or output.
     * Prevents at least tight loops, and other shenanigans.
     */
    private fun isReportAlreadyTracked(id: ReportId): Boolean = reportsReceived.containsKey(id) ||
        reportsIn.containsKey(id) ||
        reportsOut.containsKey(id)

    /**
     * track that this report is used in this Action.
     * Note: the report is already in the database.  Just need this for lineage purposes.
     */
    fun trackExistingInputReport(reportId: ReportId) {
        if (isReportAlreadyTracked(reportId)) {
            error("Bug: attempt to track history of a report ($reportId) we've already associated with this action")
        }
        val reportFile = ReportFile()
        reportFile.reportId = reportId
        reportsIn[reportId] = reportFile
    }

    /**
     * Use this to record history info about a new externally submitted report.
     */
    fun trackExternalInputReport(
        report: Report,
        blobInfo: BlobAccess.BlobInfo,
        payloadName: String? = null,
    ) {
        if (isReportAlreadyTracked(report.id)) {
            error("Bug: attempt to track history of a report ($report.id) we've already associated with this action")
        }

        val reportFile = ReportFile()
        reportFile.reportId = report.id
        // todo Is there a better way to get the sendingOrg and sendingOrgClient?
        if (report.sources.size != 1) {
            error(
                "An external incoming report should have only one source.   " +
                    "Report ${report.id} had ${report.sources.size} sources"
            )
        }
        val source = (report.sources[0] as ClientSource)
        reportFile.nextAction = report.nextAction
        reportFile.sendingOrg = source.organization
        reportFile.sendingOrgClient = source.client
        reportFile.schemaName = trimSchemaNameToMaxLength(report.schema.name)
        reportFile.schemaTopic = report.schema.topic
        reportFile.bodyUrl = blobInfo.blobUrl
        reportFile.bodyFormat = blobInfo.format.toString()
        reportFile.blobDigest = blobInfo.digest
        reportFile.externalName = payloadName
        action.externalName = payloadName
        reportFile.itemCount = report.itemCount
        reportFile.itemCountBeforeQualFilter = report.itemCountBeforeQualFilter
        reportsReceived[reportFile.reportId] = reportFile

        // check that we're dealing with an external file
        val clientSource = report.sources.firstOrNull { it is ClientSource }
        if (clientSource != null) {
            when (report.schema.topic) {
                Topic.COVID_19 -> covidResultMetadataRecords.addAll(report.getDeidentifiedCovidResults())
                else -> elrMetaDataRecords.addAll(report.getDeidentifiedResultMetaData())
            }
        }

        if (report.itemLineages != null) {
            error("For report ${report.id}:  Externally submitted reports should never have item lineage.")
        }
    }

    /**
     * Use this to record history info about a newly generated empty [report] for sending to [receiver] that
     * has requested an empty batch. The [event] will be batch or send.
     */
    fun trackGeneratedEmptyReport(event: Event, report: Report, receiver: Receiver, blobInfo: BlobAccess.BlobInfo) {
        val reportFile = ReportFile()
        reportFile.reportId = report.id

        reportFile.nextAction = TaskAction.send
        reportFile.receivingOrg = receiver.organizationName
        reportFile.receivingOrgSvc = receiver.name
        reportFile.schemaName = trimSchemaNameToMaxLength(report.schema.name)
        reportFile.schemaTopic = report.schema.topic
        reportFile.bodyUrl = blobInfo.blobUrl
        reportFile.bodyFormat = blobInfo.format.toString()
        reportFile.blobDigest = blobInfo.digest
        reportFile.itemCount = report.itemCount
        reportsReceived[reportFile.reportId] = reportFile

        // some event actions are tracked in the functions themselves, not ActionHistory.
        // TODO: Need to update this process to have a better way to determine what messages should be sent
        //  automatically as part of queueMessages and what are being send manually as part of the parent function.
        //  The automatic queueing uses the action name as the queue name, and this is not the case for FHIR actions
        if (event.eventAction != Event.EventAction.BATCH &&
            event.eventAction != Event.EventAction.DESTINATION_FILTER &&
            event.eventAction != Event.EventAction.RECEIVER_FILTER &&
            event.eventAction != Event.EventAction.TRANSLATE
        ) {
            trackEvent(event)
        }
    }

    /**
     * Track a report that was fully filtered out based on quality
     */
    fun trackFilteredReport(
        input: Report,
        report: Report,
        receiver: Receiver,
    ) {
        val reportFile = ReportFile()
        reportFile.reportId = report.id
        reportFile.receivingOrg = receiver.organizationName
        reportFile.receivingOrgSvc = receiver.name
        reportFile.schemaName = trimSchemaNameToMaxLength(report.schema.name)
        reportFile.schemaTopic = report.schema.topic
        reportFile.itemCount = report.itemCount
        reportFile.bodyFormat = report.bodyFormat.toString()
        reportFile.itemCountBeforeQualFilter = report.itemCountBeforeQualFilter
        filteredOutReports[reportFile.reportId] = reportFile
        reportLineages.add(ReportLineage(null, null, input.id, report.id, null))
        trackFilteredItems(report)
    }

    /**
     * Use this to record history info about an internally created report.
     * This also tracks the event to be queued later, as an azure message.
     */
    fun trackCreatedReport(
        event: Event,
        report: Report,
        receiver: Receiver? = null,
        blobInfo: BlobAccess.BlobInfo? = null,
        externalName: String? = null,
    ) {
        if (isReportAlreadyTracked(report.id)) {
            error("Bug:  attempt to track history of a report ($report.id) we've already associated with this action")
        }

        val reportFile = ReportFile()

        reportFile.reportId = report.id
        reportFile.schemaName = trimSchemaNameToMaxLength(report.schema.name)
        reportFile.schemaTopic = report.schema.topic
        reportFile.itemCountBeforeQualFilter = report.itemCountBeforeQualFilter

        reportFile.nextAction = event.eventAction.toTaskAction()
        reportFile.nextActionAt = event.at
        reportFile.externalName = externalName

        if (receiver != null) {
            reportFile.receivingOrg = receiver.organizationName
            reportFile.receivingOrgSvc = receiver.name
        } else if (report.destination != null) {
            // when no receiver, derive receiving org and svc from report destination
            reportFile.receivingOrg = report.destination.organizationName
            reportFile.receivingOrgSvc = report.destination.name
        }

        if (blobInfo != null) {
            reportFile.bodyUrl = blobInfo.blobUrl
            reportFile.bodyFormat = blobInfo.format.toString()
            reportFile.blobDigest = blobInfo.digest
            reportFile.itemCount = report.itemCount
        } else {
            reportFile.bodyFormat = MimeFormat.FHIR.toString() // currently only the UP sends null blobs
            reportFile.itemCount = 0
        }

        reportsOut[reportFile.reportId] = reportFile
        trackFilteredItems(report)
        trackItemLineages(report)

        // some event actions are tracked in the functions themselves, not ActionHistory.
        // TODO: Need to update this process to have a better way to determine what messages should be sent
        //  automatically as part of queueMessages and what are being send manually as part of the parent function.
        //  The automatic queueing uses the action name as the queue name, and this is not the case for FHIR actions
        if (event.eventAction != Event.EventAction.BATCH &&
            event.eventAction != Event.EventAction.DESTINATION_FILTER &&
            event.eventAction != Event.EventAction.RECEIVER_FILTER &&
            event.eventAction != Event.EventAction.TRANSLATE
        ) {
            trackEvent(event) // to be sent to queue later.
        }
    }

    fun trackSentReport(
        receiver: Receiver,
        sentReportId: ReportId,
        filename: String?,
        params: String,
        result: String,
        header: WorkflowEngine.Header,
        reportEventService: IReportStreamEventService,
        reportService: ReportService,
        transportType: String,
    ) {
        if (isReportAlreadyTracked(sentReportId)) {
            error(
                "Bug:  attempt to track history of a report ($sentReportId) " +
                    "we've already associated with this action"
            )
        }

        if (header.content == null) {
            error("Bug: attempt to track sent report with no contents")
        }

        val blobInfo = BlobAccess.uploadBody(
            receiver.format,
            header.content,
            sentReportId.toString(),
            receiver.fullName,
            Event.EventAction.NONE
        )

        val reportFile = ReportFile()
        reportFile.reportId = sentReportId
        reportFile.receivingOrg = receiver.organizationName
        reportFile.receivingOrgSvc = receiver.name
        reportFile.schemaName = trimSchemaNameToMaxLength(receiver.schemaName)
        reportFile.schemaTopic = receiver.topic
        reportFile.externalName = filename
        action.externalName = filename
        reportFile.transportParams = params
        reportFile.transportResult = result
        reportFile.bodyFormat = receiver.format.toString()
        reportFile.itemCount = header.reportFile.itemCount
        reportFile.blobDigest = blobInfo.digest
        reportFile.bodyUrl = blobInfo.blobUrl

        reportEventService.sendReportEvent(
            childReport = reportFile,
            eventName = ReportStreamEventName.REPORT_SENT,
            pipelineStepName = TaskAction.send
        ) {
            parentReportId(header.reportFile.reportId)
            params(
                listOfNotNull(
                    ReportStreamEventProperties.TRANSPORT_TYPE to transportType,
                    ReportStreamEventProperties.RECEIVER_NAME to receiver.fullName,
                    filename?.let { ReportStreamEventProperties.FILENAME to it }
                ).toMap()
            )
        }

        val lineages = Report.createItemLineagesFromDb(header, sentReportId)
        lineages?.forEach { itemLineage ->
            val receiverFilterReportFile = reportService.getReportForItemAtTask(
                itemLineage.parentReportId,
                itemLineage.parentIndex,
                TaskAction.receiver_filter
            )
            if (receiverFilterReportFile != null) {
                val blob = BlobAccess.downloadBlob(
                    receiverFilterReportFile.bodyUrl,
                    BlobUtils.digestToString(receiverFilterReportFile.blobDigest)
                )
                val bundle = FhirTranscoder.decode(blob)
                val bundleDigestExtractor = BundleDigestExtractor(
                    FhirPathBundleDigestLabResultExtractorStrategy(
                        CustomContext(
                            bundle,
                            bundle,
                            mutableMapOf(),
                            CustomFhirPathFunctions()
                        )
                    )
                )
                reportEventService.sendItemEvent(ReportStreamEventName.ITEM_SENT, reportFile, TaskAction.send) {
                    trackingId(bundle)
                    parentReportId(header.reportFile.reportId)
                    childItemIndex(itemLineage.childIndex)
                    params(
                        mapOf(
                            ReportStreamEventProperties.BUNDLE_DIGEST
                                to bundleDigestExtractor.generateDigest(bundle),
                            ReportStreamEventProperties.RECEIVER_NAME to receiver.fullName,
                        )
                    )
                }
            } else {
                logger.error("No translate report found for sent item.")
            }
        }
        reportsOut[reportFile.reportId] = reportFile
    }

    /**
     * Note that confusingly the downloadedReportId is NOT the UUID of the blob that got downloaded.
     * Its a brand new UUID, that artificially represents the copy of the report that is now outside
     * of our custody.
     */
    fun trackDownloadedReport(
        parentReportFile: ReportFile,
        externalReportId: ReportId,
        downloadedBy: String,
    ) {
        trackExistingInputReport(parentReportFile.reportId)
        if (isReportAlreadyTracked(externalReportId)) {
            error(
                "Bug:  attempt to track history of a report ($externalReportId)" +
                    " we've already associated with this action"
            )
        }
        val reportFile = ReportFile()
        reportFile.reportId = externalReportId // child report
        reportFile.receivingOrg = parentReportFile.receivingOrg
        reportFile.receivingOrgSvc = parentReportFile.receivingOrgSvc
        reportFile.schemaName = trimSchemaNameToMaxLength(parentReportFile.schemaName)
        reportFile.schemaTopic = parentReportFile.schemaTopic
        reportFile.externalName = parentReportFile.externalName
        action.externalName = parentReportFile.externalName
        reportFile.transportParams = "{ \"reportRequested\": \"${parentReportFile.reportId}\"}"
        reportFile.transportResult = "{ \"downloadedBy\": \"$downloadedBy\"}"
        reportFile.bodyUrl = null // this entry represents an external file, not a blob.
        reportFile.bodyFormat = parentReportFile.bodyFormat
        reportFile.blobDigest = null // no blob
        reportFile.itemCount = parentReportFile.itemCount
        reportFile.downloadedBy = downloadedBy
        reportsOut[reportFile.reportId] = reportFile

        trackUsername(downloadedBy)
    }

    /**
     * Record the filtered items in a report as Events in the Action
     *
     * NOTE: Needs to be done only once a report is tracked
     * otherwise the report referenced by the Detail
     * will not match the report that is tracked and stored.
     *
     * If the behavior of a Report ID changing during interim
     * transformations changes these details can be tracked sooner.
     *
     * @param report The report who's *filter results* to record
     */
    internal fun trackFilteredItems(report: Report) {
        report.filteringResults.forEach {
            trackLogs(
                ActionLog(
                    it,
                    it.filteredTrackingElement,
                    null, // we don't have accurate filteredIndex (rownums) to put here; due to juri filtering.
                    reportId = report.id,
                    action = action,
                    type = ActionLogLevel.filter,
                )
            )
        }
    }

    private fun trackItemLineages(report: Report) {
        // sanity checks
        if (report.itemLineages == null) error("Cannot create lineage For report ${report.id}: missing ItemLineage")
        if (report.itemLineages!!.size != report.itemCount) {
            error(
                "Report ${report.id} should have ${report.itemCount} lineage items" +
                    " but instead has ${report.itemLineages!!.size} lineage items"
            )
        }
        trackItemLineages(report.itemLineages)
    }

    fun trackItemLineages(itemLineages: List<ItemLineage>?) {
        if (itemLineages == null) return
        this.itemLineages.addAll(itemLineages)
    }

    fun queueMessages(workflowEngine: WorkflowEngine) {
        messages.forEach { event ->
            workflowEngine.queue.sendMessage(event)
            logger.debug("Queued event: ${event.toQueueMessage()}")
        }
    }

    /**
     * Use the detailed item lineage to exactly/correctly generate the report parent/child relationships.
     *
     */
    internal fun generateReportLineagesUsingItemLineage(actionId: Long) {
        // Extract the distinct parent/child report pairs from the Item Lineage
        val parentChildReports = itemLineages.map { Pair(it.parentReportId, it.childReportId) }.toSet()
        parentChildReports.forEach {
            reportLineages.add(ReportLineage(null, actionId, it.first, it.second, null))
        }

        // If an action has no children, it has no lineage.
        if (reportsOut.isEmpty() && parentChildReports.isEmpty()) return // no lineage assoc with this action.

        // sanity should prevail, at least in ReportStream, if not in general
        if (reportsOut.isNotEmpty() && parentChildReports.isEmpty()) {
            error("There are child reports (${reportsOut.keys.joinToString(",")}) but no item lineages")
        }
        if (reportsOut.isEmpty() && parentChildReports.isNotEmpty()) {
            error("There are item lineages (${parentChildReports.joinToString(",")}) but no child reports")
        }
        // compare the set of reportIds from the item lineage vs the set from report lineage.  Should be identical.
        val parentReports = parentChildReports.map { it.first }.toSet()
        val childReports = parentChildReports.map { it.second }.toSet()
        val parentReports2 = mutableSetOf<ReportId>()
        parentReports2.addAll(reportsReceived.keys)
        parentReports2.addAll(reportsIn.keys)
        val childReports2 = reportsOut.keys
        if (parentReports != parentReports2) {
            error(
                "parent reports from items (${parentReports.joinToString(",")}) != from reports" +
                    "(${parentReports2.joinToString(",")})"
            )
        }
        if (childReports != childReports2) {
            error(
                "child reports from items (${childReports.joinToString(",")} != from reports" +
                    "(${childReports2.joinToString(",")})"
            )
        }
        logger.debug("There are ${reportLineages.size} parent->child report-level relationships")
    }

    companion object : Logging {

        // The schema_name column only support 63 characters
        // If the schemaName is a URI grab the path and then take the last 63
        // otherwise just take the last 63
        // TODO: #13598
        fun trimSchemaNameToMaxLength(schemaName: String?): String? {
            if (schemaName == null) {
                return schemaName
            }
            return try {
                URI(schemaName).path.replace(".yml", "").takeLast(63)
            } catch (ex: URISyntaxException) {
                schemaName.takeLast(63)
            }
        }

        /**
         * Get rid of this once we have moved away from the old Task table.  In the meantime,
         * this is a way of confirming that the new tables are robust.
         */
        fun sanityCheckReports(
            tasks: List<Task>?,
            reportFiles: Map<ReportId, ReportFile>?,
            failOnError: Boolean = false,
        ) {
            var msg = ""
            if (tasks == null) {
                msg = "headers is null"
            } else {
                if (reportFiles == null) {
                    msg = "reportFiles is null"
                } else {
                    if (tasks.size != reportFiles.size) {
                        msg = "Different report_file count: Got ${tasks.size} TASKS," +
                            " but ${reportFiles.size} reportFiles.  " +
                            "*** TASK ids: ${tasks.map { it.reportId }.toSortedSet().joinToString(",")}  " +
                            "*** REPORT_FILE ids:${reportFiles.map { it.key }.toSortedSet().joinToString(",")}"
                    } else {
                        tasks.forEach {
                            sanityCheckReport(it, reportFiles[it.reportId], failOnError)
                        }
                    }
                }
            }
            if (msg.isNotEmpty()) {
                if (failOnError) {
                    error("*** Sanity check comparing old Headers list to new ReportFile list FAILED:  $msg")
                } else {
                    logger.warn("***** FAILURE: sanity check comparing old headers list to new ReportFiles list:\n$msg")
                }
            }
        }

        /**
         * Get rid of this once we have moved away from the old Task table.  In the meantime,
         * this is a way of confirming that the new tables are robust.
         */
        fun sanityCheckReport(task: Task?, reportFile: ReportFile?, failOnError: Boolean = false) {
            var msg = ""
            if (task == null) {
                msg = "header is null"
            } else {
                if (reportFile == null) {
                    msg = "reportFile is null - no matching report was retrieved with ${task.reportId}"
                } else {
                    if (task.bodyFormat != reportFile.bodyFormat) {
                        msg = "header.bodyFormat = ${task.bodyFormat}, " +
                            "but reportFile.bodyFormat= ${reportFile.bodyFormat}, "
                    }
                    if (task.bodyUrl != reportFile.bodyUrl) {
                        msg += "header.bodyUrl = ${task.bodyUrl}, but reportFile.bodyFormat= ${reportFile.bodyUrl}, "
                    }
                    if (task.itemCount != reportFile.itemCount) {
                        msg += "header.itemCount = ${task.itemCount}, " +
                            "but reportFile.itemCount= ${reportFile.itemCount}, "
                    }
                    if (task.receiverName != (reportFile.receivingOrg + "." + reportFile.receivingOrgSvc)) {
                        msg += "header.receiverName = ${task.receiverName}, but reportFile has " +
                            (reportFile.receivingOrg + "." + reportFile.receivingOrgSvc)
                    }
                    if (task.reportId != reportFile.reportId) {
                        msg += "header.reportId = ${task.reportId}, but reportFile.reportId= ${reportFile.reportId}, "
                    }
                }
            }
            if (msg.isNotEmpty()) {
                if (failOnError) {
                    error("*** Sanity check comparing old Header info and new ReportFile info FAILED:  $msg")
                } else {
                    logger.warn("***** FAILURE: sanity check comparing old headers list to new ReportFiles list:\n$msg")
                }
            }
        }
    }
}