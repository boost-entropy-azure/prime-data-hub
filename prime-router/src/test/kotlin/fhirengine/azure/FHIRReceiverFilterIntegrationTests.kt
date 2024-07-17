package gov.cdc.prime.router.fhirengine.azure

import assertk.assertThat
import assertk.assertions.hasSameSizeAs
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isEqualToIgnoringGivenProperties
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isZero
import assertk.assertions.matchesPredicate
import gov.cdc.prime.router.ActionLog
import gov.cdc.prime.router.ActionLogLevel
import gov.cdc.prime.router.ActionLogScope
import gov.cdc.prime.router.CodeStringConditionFilter
import gov.cdc.prime.router.DeepOrganization
import gov.cdc.prime.router.FileSettings
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.ReportStreamFilter
import gov.cdc.prime.router.ReportStreamFilterType
import gov.cdc.prime.router.Sender
import gov.cdc.prime.router.Topic
import gov.cdc.prime.router.azure.BlobAccess
import gov.cdc.prime.router.azure.DatabaseLookupTableAccess
import gov.cdc.prime.router.azure.Event
import gov.cdc.prime.router.azure.QueueAccess
import gov.cdc.prime.router.azure.db.Tables
import gov.cdc.prime.router.azure.db.enums.TaskAction
import gov.cdc.prime.router.azure.observability.event.AzureEventService
import gov.cdc.prime.router.azure.observability.event.AzureEventUtils
import gov.cdc.prime.router.azure.observability.event.LocalAzureEventServiceImpl
import gov.cdc.prime.router.azure.observability.event.ReceiverFilterFailedEvent
import gov.cdc.prime.router.azure.observability.event.ReportRouteEvent
import gov.cdc.prime.router.cli.ObservationMappingConstants
import gov.cdc.prime.router.common.TestcontainersUtils
import gov.cdc.prime.router.common.UniversalPipelineTestUtils
import gov.cdc.prime.router.common.validFHIRRecord1
import gov.cdc.prime.router.common.validFHIRRecord1Identifier
import gov.cdc.prime.router.db.ReportStreamTestDatabaseContainer
import gov.cdc.prime.router.db.ReportStreamTestDatabaseSetupExtension
import gov.cdc.prime.router.fhirengine.engine.FHIRReceiverFilter
import gov.cdc.prime.router.fhirengine.engine.FhirTranslateQueueMessage
import gov.cdc.prime.router.fhirengine.engine.elrTranslationQueueName
import gov.cdc.prime.router.fhirengine.utils.FhirTranscoder
import gov.cdc.prime.router.fhirengine.utils.addMappedConditions
import gov.cdc.prime.router.fhirengine.utils.deleteResource
import gov.cdc.prime.router.fhirengine.utils.getObservations
import gov.cdc.prime.router.history.db.ReportGraph
import gov.cdc.prime.router.metadata.LookupTable
import gov.cdc.prime.router.report.ReportService
import gov.cdc.prime.router.unittest.UnitTestUtils
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.apache.logging.log4j.kotlin.Logging
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.util.UUID

private const val VALID_FHIR_URL = "src/test/resources/fhirengine/engine/valid_data.fhir"

private const val MULTIPLE_OBSERVATIONS_FHIR_URL =
    "src/test/resources/fhirengine/engine/bundle_multiple_observations.fhir"

@Testcontainers
@ExtendWith(ReportStreamTestDatabaseSetupExtension::class)
class FHIRReceiverFilterIntegrationTests : Logging {

    // Must have message ID, patient last name, patient first name, DOB, specimen type
    // At least one of patient street, patient zip code, patient phone number, patient email
    // At least one of order test date, specimen collection date/time, test result date
    val fullElrQualityFilterSample: ReportStreamFilter = listOf(
        "%messageId.exists()",
        "%patient.name.family.exists()",
        "%patient.name.given.count() > 0",
        "%patient.birthDate.exists()",
        "%specimen.type.exists()",
        "(%patient.address.line.exists() or " +
            "%patient.address.postalCode.exists() or " +
            "%patient.telecom.exists())",
        "(" +
            "(%specimen.collection.collectedPeriod.exists() or " +
            "%specimen.collection.collected.exists()" +
            ") or " +
            "%serviceRequest.occurrence.exists() or " +
            "%observation.effective.exists())",
    )

    // requires only an id exists in the message header
    val simpleElrQualifyFilter: ReportStreamFilter = listOf(
        "Bundle.entry.resource.ofType(MessageHeader).id.exists()"
    )

    // Must have a processing mode set to production
    val processingModeFilterProduction: ReportStreamFilter = listOf(
        "Bundle.entry.resource.ofType(MessageHeader).meta.tag.where(" +
            "system = 'http://terminology.hl7.org/CodeSystem/v2-0103'" +
            ").code.exists() " +
            "and " +
            "Bundle.entry.resource.ofType(MessageHeader).meta.tag.where(" +
            "system = 'http://terminology.hl7.org/CodeSystem/v2-0103'" +
            ").code = 'P'"
    )

    // Must have a processing mode id of debugging
    val processingModeFilterDebugging: ReportStreamFilter = listOf(
        "Bundle.entry.resource.ofType(MessageHeader).meta.tag.where(" +
            "system = 'http://terminology.hl7.org/CodeSystem/v2-0103'" +
            ").code.exists() " +
            "and " +
            "Bundle.entry.resource.ofType(MessageHeader).meta.tag.where(" +
            "system = 'http://terminology.hl7.org/CodeSystem/v2-0103'" +
            ").code = 'D'"
    )

    // only allow observations that have 94558-5.
    val conditionFilter: ReportStreamFilter = listOf(
        "%resource.code.coding.code='94558-5'"
    )
    val noneConditionFilter: ReportStreamFilter = listOf(
        "%resource.code.coding.code='1234'"
    )

    val noneMappedConditionFilter = listOf(CodeStringConditionFilter("foobar"))

    val observationMappingMetadata = UnitTestUtils.simpleMetadata.apply {
        this.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable(
                "observation-mapping",
                listOf(
                    listOf(
                        ObservationMappingConstants.TEST_CODE_KEY,
                        ObservationMappingConstants.CONDITION_CODE_KEY,
                        ObservationMappingConstants.CONDITION_CODE_SYSTEM_KEY,
                        ObservationMappingConstants.CONDITION_NAME_KEY
                    ),
                    listOf(
                        "94558-5",
                        "flu",
                        "SNOMEDCT",
                        "Influenza (disorder)"
                    )
                )
            )
        )
    }

    @Container
    val azuriteContainer = TestcontainersUtils.createAzuriteContainer(
        customImageName = "azurite_fhirfunctionintegration1",
        customEnv = mapOf(
            "AZURITE_ACCOUNTS" to "devstoreaccount1:keydevstoreaccount1"
        )
    )

    val azureEventService = LocalAzureEventServiceImpl()

    @BeforeEach
    fun beforeEach() {
        mockkObject(QueueAccess)
        every { QueueAccess.sendMessage(any(), any()) } returns Unit
        mockkObject(BlobAccess)
        every { BlobAccess getProperty "defaultBlobMetadata" } returns UniversalPipelineTestUtils
            .getBlobContainerMetadata(azuriteContainer)
        mockkObject(BlobAccess.BlobContainerMetadata)
        every {
            BlobAccess.BlobContainerMetadata.build(
                any(),
                any()
            )
        } returns UniversalPipelineTestUtils.getBlobContainerMetadata(azuriteContainer)
        // TODO consider not mocking DatabaseLookupTableAccess
        mockkConstructor(DatabaseLookupTableAccess::class)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
        azureEventService.events.clear()
    }

    fun createReceiverFilter(
        azureEventService: AzureEventService,
        org: DeepOrganization? = null,
    ): FHIRReceiverFilter {
        val settings = FileSettings().loadOrganizations(org ?: UniversalPipelineTestUtils.universalPipelineOrganization)
        val metadata = UnitTestUtils.simpleMetadata
        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )
        return FHIRReceiverFilter(
            metadata,
            settings,
            reportService = ReportService(ReportGraph(ReportStreamTestDatabaseContainer.testDatabaseAccess)),
            azureEventService = azureEventService
        )
    }

    fun generateQueueMessage(
        report: Report,
        blobContents: String,
        sender: Sender,
        receiverName: String,
    ): String {
        return """
            {
                "type": "${TaskAction.receiver_filter.literal}",
                "reportId": "${report.id}",
                "blobURL": "${report.bodyURL}",
                "digest": "${BlobAccess.digestToString(BlobAccess.sha256Digest(blobContents.toByteArray()))}",
                "blobSubFolderName": "${sender.fullName}",
                "topic": "${sender.topic.jsonVal}",
                "receiverFullName": "$receiverName" 
            }
        """.trimIndent()
    }

    @Test
    fun `should send valid FHIR report filtered by condition filter`() {
        // set up
        val receiverSetupData = listOf(
            UniversalPipelineTestUtils.ReceiverSetupData(
                "x",
                jurisdictionalFilter = listOf("true"),
                qualityFilter = listOf("true"),
                routingFilter = listOf("true"),
                conditionFilter = conditionFilter
            )
        )
        val receivers = UniversalPipelineTestUtils.createReceivers(receiverSetupData)
        val receiver = receivers.single()
        val org = UniversalPipelineTestUtils.createOrganizationWithReceivers(receivers)
        val receiverFilter = createReceiverFilter(azureEventService, org)
        val reportContents = File(MULTIPLE_OBSERVATIONS_FHIR_URL).readText()
        val report = UniversalPipelineTestUtils.createReport(
            reportContents,
            TaskAction.receiver_filter,
            Event.EventAction.RECEIVER_FILTER,
            azuriteContainer
        )
        val queueMessage = generateQueueMessage(
            report,
            reportContents,
            UniversalPipelineTestUtils.fhirSenderWithNoTransform,
            "phd.x"
        )
        val fhirFunctions = UniversalPipelineTestUtils.createFHIRFunctionsInstance()

        // execute
        fhirFunctions.doReceiverFilter(queueMessage, 1, receiverFilter)

        // check results
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routedReport = UniversalPipelineTestUtils.fetchChildReports(report, txn, 1).single()
            val routedContents = String(
                BlobAccess.downloadBlobAsByteArray(
                routedReport.bodyUrl,
                UniversalPipelineTestUtils.getBlobContainerMetadata(azuriteContainer)
            )
            )
            val routedBundle = FhirTranscoder.decode(routedContents)

            // check observations
            assertThat(routedBundle.getObservations()).hasSize(1)
            assertThat(routedBundle.getObservations().first().code.coding).hasSize(1)
            assertThat(routedBundle.getObservations().first().code.coding.first().code).isEqualTo("94558-5")
            val expectedBundle = FhirTranscoder.decode(reportContents).apply {
                this.getObservations().forEach {
                    if (it.code.coding.first().code != "94558-5") {
                        this.deleteResource(it)
                    }
                }
            }
            assertThat(FhirTranscoder.encode(expectedBundle)).isEqualTo(FhirTranscoder.encode(routedBundle))

            // check queue message
            val expectedRouteQueueMessage = FhirTranslateQueueMessage(
                routedReport.reportId,
                routedReport.bodyUrl,
                BlobAccess.digestToString(routedReport.blobDigest),
                "phd.fhir-elr-no-transform",
               Topic.FULL_ELR,
                receiver.fullName
            ).serialize()

            verify(exactly = 1) {
                QueueAccess.sendMessage(elrTranslationQueueName, expectedRouteQueueMessage)
            }

            // check events
            assertThat(azureEventService.events).hasSize(1)
            val bundle = FhirTranscoder.decode(reportContents)
            assertThat(azureEventService.events.single())
                .isInstanceOf<ReportRouteEvent>()
                .isEqualTo(
                    ReportRouteEvent(
                        routedReport.reportId,
                        report.id,
                        report.id,
                        Topic.FULL_ELR,
                        "phd.Test Sender",
                        receiver.fullName,
                        AzureEventUtils.getObservationSummaries(routedBundle),
                        AzureEventUtils.getObservationSummaries(
                            bundle.getObservations().filter { it.code.coding.first().code != "94558-5" }
                        ),
                        routedContents.length,
                        AzureEventUtils.getIdentifier(routedBundle)
                    )
                )

            // check action table
            UniversalPipelineTestUtils.checkActionTable(listOf(TaskAction.receive, TaskAction.receiver_filter))
        }
    }

    @Test
    fun `should send valid FHIR report with no condition related filtering`() {
        // set up
        val receiverSetupData = listOf(
            UniversalPipelineTestUtils.ReceiverSetupData(
                "y",
                jurisdictionalFilter = listOf("true"),
                qualityFilter = listOf("true"),
                routingFilter = listOf("true"),
                conditionFilter = listOf("true")
            )
        )
        val receivers = UniversalPipelineTestUtils.createReceivers(receiverSetupData)
        val receiver = receivers.single()
        val org = UniversalPipelineTestUtils.createOrganizationWithReceivers(receivers)
        val receiverFilter = createReceiverFilter(azureEventService, org)
        val reportContents = File(MULTIPLE_OBSERVATIONS_FHIR_URL).readText()
        val report = UniversalPipelineTestUtils.createReport(
            reportContents,
            TaskAction.receiver_filter,
            Event.EventAction.RECEIVER_FILTER,
            azuriteContainer
        )
        val queueMessage = generateQueueMessage(
            report,
            reportContents,
            UniversalPipelineTestUtils.fhirSenderWithNoTransform,
            receiver.fullName
        )
        val fhirFunctions = UniversalPipelineTestUtils.createFHIRFunctionsInstance()

        // execute
        fhirFunctions.doReceiverFilter(queueMessage, 1, receiverFilter)

        // check results
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routedReport = UniversalPipelineTestUtils.fetchChildReports(report, txn, 1)
                .single()
            val routedContents = String(
                BlobAccess.downloadBlobAsByteArray(
                routedReport.bodyUrl,
                UniversalPipelineTestUtils.getBlobContainerMetadata(azuriteContainer)
            )
            )
            assertThat(routedContents).isEqualTo(reportContents)

            // check queue message
            val expectedQueueMessage = FhirTranslateQueueMessage(
                routedReport.reportId,
                routedReport.bodyUrl,
                BlobAccess.digestToString(routedReport.blobDigest),
                "phd.fhir-elr-no-transform",
               Topic.FULL_ELR,
                receiver.fullName
            ).serialize()

            verify(exactly = 1) {
                QueueAccess.sendMessage(
                    elrTranslationQueueName,
                    expectedQueueMessage
                )
            }

            // check events
            assertThat(azureEventService.events).hasSize(1)
            val routedBundle = FhirTranscoder.decode(routedContents)
            assertThat(azureEventService.events.single()).isEqualTo(
                ReportRouteEvent(
                    routedReport.reportId,
                    report.id,
                    report.id,
                    Topic.FULL_ELR,
                    "phd.Test Sender",
                    receiver.fullName,
                    AzureEventUtils.getObservationSummaries(FhirTranscoder.decode(reportContents)),
                    emptyList(),
                    routedContents.length,
                    AzureEventUtils.getIdentifier(routedBundle)
                )
            )

            // check action table
            UniversalPipelineTestUtils.checkActionTable(listOf(TaskAction.receive, TaskAction.receiver_filter))
        }
    }

    @Test
    fun `should not send report fully pruned by condition filter`() {
        // set up
        val receiverSetupData = listOf(
            UniversalPipelineTestUtils.ReceiverSetupData(
                "x",
                jurisdictionalFilter = listOf("true"),
                qualityFilter = listOf("true"),
                routingFilter = listOf("true"),
                conditionFilter = noneConditionFilter
            )
        )
        val receivers = UniversalPipelineTestUtils.createReceivers(receiverSetupData)
        val receiver = receivers.single()
        val org = UniversalPipelineTestUtils.createOrganizationWithReceivers(receivers)
        val receiverFilter = createReceiverFilter(azureEventService, org)
        val reportContents = File(MULTIPLE_OBSERVATIONS_FHIR_URL).readText()
        val report = UniversalPipelineTestUtils.createReport(
            reportContents,
            TaskAction.receiver_filter,
            Event.EventAction.RECEIVER_FILTER,
            azuriteContainer
        )
        val queueMessage = generateQueueMessage(
            report,
            reportContents,
            UniversalPipelineTestUtils.fhirSenderWithNoTransform,
            "phd.x"
        )
        val fhirFunctions = UniversalPipelineTestUtils.createFHIRFunctionsInstance()

        // execute
        fhirFunctions.doReceiverFilter(queueMessage, 1, receiverFilter)

        // check results
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            // check terminated lineage
            val routedReport = UniversalPipelineTestUtils.fetchChildReports(report, txn, 1).single()
            assertThat(routedReport.nextAction).isEqualTo(TaskAction.none)
            assertThat(routedReport.bodyUrl).isNull()
            assertThat(routedReport.schemaTopic).isEqualTo(Topic.FULL_ELR)
            assertThat(routedReport.bodyFormat).isEqualTo("FHIR")
            assertThat(routedReport.itemCount).isZero()

            // check for no queue message
            verify(exactly = 0) {
                QueueAccess.sendMessage(any(), any())
            }

            // check events
            assertThat(azureEventService.events).hasSize(1)
            val bundle = FhirTranscoder.decode(reportContents)
            assertThat(azureEventService.events.single())
                .isInstanceOf<ReceiverFilterFailedEvent>()
                .isEqualToIgnoringGivenProperties(
                    ReceiverFilterFailedEvent(
                        UUID.randomUUID(), // ignored
                        report.id,
                        report.id,
                        Topic.FULL_ELR,
                        "phd.Test Sender",
                        receiver.fullName,
                        AzureEventUtils.getObservationSummaries(bundle),
                        noneConditionFilter,
                        ReportStreamFilterType.CONDITION_FILTER,
                        reportContents.length,
                        AzureEventUtils.getIdentifier(bundle)
                    ),
                    ReceiverFilterFailedEvent::reportId
                )

            // check action table
            UniversalPipelineTestUtils.checkActionTable(listOf(TaskAction.receive, TaskAction.receiver_filter))
        }
    }

    @Test
    fun `should send valid FHIR report filtered by mapped condition filter`() {
        // set up
        val receiverSetupData = listOf(
            UniversalPipelineTestUtils.ReceiverSetupData(
                "x",
                jurisdictionalFilter = listOf("true"),
                qualityFilter = listOf("true"),
                routingFilter = listOf("true"),
                mappedConditionFilter = listOf(CodeStringConditionFilter("flu"))
            )
        )
        val receivers = UniversalPipelineTestUtils.createReceivers(receiverSetupData)
        val receiver = receivers.single()
        val org = UniversalPipelineTestUtils.createOrganizationWithReceivers(receivers)
        val receiverFilter = createReceiverFilter(azureEventService, org)
        val reportContents = File(MULTIPLE_OBSERVATIONS_FHIR_URL).readText()
        val bundle = FhirTranscoder.decode(reportContents)
        bundle.getObservations().forEach {
            it.addMappedConditions(observationMappingMetadata)
        }
        val stampedReportContents = FhirTranscoder.encode(bundle)
        val report = UniversalPipelineTestUtils.createReport(
            stampedReportContents,
            TaskAction.receiver_filter,
            Event.EventAction.RECEIVER_FILTER,
            azuriteContainer
        )
        val queueMessage = generateQueueMessage(
            report,
            stampedReportContents,
            UniversalPipelineTestUtils.fhirSenderWithNoTransform,
            "phd.x"
        )
        val fhirFunctions = UniversalPipelineTestUtils.createFHIRFunctionsInstance()

        // execute
        fhirFunctions.doReceiverFilter(queueMessage, 1, receiverFilter)

        // check results
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routedReport = UniversalPipelineTestUtils.fetchChildReports(report, txn, 1).single()
            val routedContents = String(
                BlobAccess.downloadBlobAsByteArray(
                routedReport.bodyUrl,
                UniversalPipelineTestUtils.getBlobContainerMetadata(azuriteContainer)
            )
            )
            val routedBundle = FhirTranscoder.decode(routedContents)

            // check observations
            assertThat(routedBundle.getObservations()).hasSize(1)
            assertThat(routedBundle.getObservations().first().code.coding).hasSize(1)
            assertThat(routedBundle.getObservations().first().code.coding.first().code).isEqualTo("94558-5")
            val expectedBundle = bundle.copy()
            expectedBundle.getObservations().forEach {
                if (it.code.coding.first().code != "94558-5") {
                    expectedBundle.deleteResource(it)
                }
            }
            assertThat(FhirTranscoder.encode(expectedBundle)).isEqualTo(FhirTranscoder.encode(routedBundle))

            // check queue message
            val expectedRouteQueueMessage = FhirTranslateQueueMessage(
                routedReport.reportId,
                routedReport.bodyUrl,
                BlobAccess.digestToString(routedReport.blobDigest),
                "phd.fhir-elr-no-transform",
                Topic.FULL_ELR,
                receiver.fullName
            ).serialize()

            verify(exactly = 1) {
                QueueAccess.sendMessage(elrTranslationQueueName, expectedRouteQueueMessage)
            }

            // check events
            assertThat(azureEventService.events).hasSize(1)
            assertThat(azureEventService.events.single())
                .isInstanceOf<ReportRouteEvent>()
                .isEqualTo(
                    ReportRouteEvent(
                        routedReport.reportId,
                        report.id,
                        report.id,
                        Topic.FULL_ELR,
                        "phd.Test Sender",
                        receiver.fullName,
                        AzureEventUtils.getObservationSummaries(routedBundle),
                        AzureEventUtils.getObservationSummaries(
                            bundle.getObservations().filter { it.code.coding.first().code != "94558-5" }
                        ),
                        routedContents.length,
                        AzureEventUtils.getIdentifier(routedBundle)
                    )
                )

            // check action table
            UniversalPipelineTestUtils.checkActionTable(listOf(TaskAction.receive, TaskAction.receiver_filter))
        }
    }

    @Test
    fun `should not send report fully pruned by mapped condition filter`() {
        // set up
        val receiverSetupData = listOf(
            UniversalPipelineTestUtils.ReceiverSetupData(
                "x",
                jurisdictionalFilter = listOf("true"),
                qualityFilter = listOf("true"),
                routingFilter = listOf("true"),
                mappedConditionFilter = noneMappedConditionFilter
            )
        )
        val receivers = UniversalPipelineTestUtils.createReceivers(receiverSetupData)
        val receiver = receivers.single()
        val org = UniversalPipelineTestUtils.createOrganizationWithReceivers(receivers)
        val receiverFilter = createReceiverFilter(azureEventService, org)
        val reportContents = File(MULTIPLE_OBSERVATIONS_FHIR_URL).readText()
        val report = UniversalPipelineTestUtils.createReport(
            reportContents,
            TaskAction.receiver_filter,
            Event.EventAction.RECEIVER_FILTER,
            azuriteContainer
        )
        val queueMessage = generateQueueMessage(
            report,
            reportContents,
            UniversalPipelineTestUtils.fhirSenderWithNoTransform,
            "phd.x"
        )
        val fhirFunctions = UniversalPipelineTestUtils.createFHIRFunctionsInstance()

        // execute
        fhirFunctions.doReceiverFilter(queueMessage, 1, receiverFilter)

        // check results
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routedReport = UniversalPipelineTestUtils.fetchChildReports(report, txn, 1).single()
            assertThat(routedReport.nextAction).isEqualTo(TaskAction.none)
            assertThat(routedReport.bodyUrl).isNull()
            assertThat(routedReport.schemaTopic).isEqualTo(Topic.FULL_ELR)
            assertThat(routedReport.bodyFormat).isEqualTo("FHIR")
            assertThat(routedReport.itemCount).isZero()

            // check queue message
            verify(exactly = 0) {
                QueueAccess.sendMessage(any(), any())
            }

            // check events
            assertThat(azureEventService.events).hasSize(1)
            val bundle = FhirTranscoder.decode(reportContents)
            assertThat(azureEventService.events.single())
                .isInstanceOf<ReceiverFilterFailedEvent>()
                .isEqualToIgnoringGivenProperties(
                    ReceiverFilterFailedEvent(
                        UUID.randomUUID(),
                        report.id,
                        report.id,
                        Topic.FULL_ELR,
                        "phd.Test Sender",
                        receiver.fullName,
                        AzureEventUtils.getObservationSummaries(bundle),
                        noneMappedConditionFilter.map { it.toString() },
                        ReportStreamFilterType.MAPPED_CONDITION_FILTER,
                        reportContents.length,
                        AzureEventUtils.getIdentifier(bundle)
                    ),
                    ReceiverFilterFailedEvent::reportId
                )

            // check action table
            UniversalPipelineTestUtils.checkActionTable(listOf(TaskAction.receive, TaskAction.receiver_filter))
        }
    }

    @Test
    fun `should respect full quality filter and not send message`() {
        // set up
        val receiverSetupData = listOf(
            UniversalPipelineTestUtils.ReceiverSetupData(
                "x",
                jurisdictionalFilter = listOf("true"),
                routingFilter = listOf("true"),
                qualityFilter = fullElrQualityFilterSample
            )
        )
        val receivers = UniversalPipelineTestUtils.createReceivers(receiverSetupData)
        val receiver = receivers.single()
        val org = UniversalPipelineTestUtils.createOrganizationWithReceivers(receivers)
        val receiverFilter = createReceiverFilter(azureEventService, org)
        val reportContents = validFHIRRecord1
        val report = UniversalPipelineTestUtils.createReport(
            reportContents,
            TaskAction.receiver_filter,
            Event.EventAction.RECEIVER_FILTER,
            azuriteContainer
        )
        val queueMessage = generateQueueMessage(
            report,
            reportContents,
            UniversalPipelineTestUtils.fhirSenderWithNoTransform,
            receiver.fullName
        )
        val fhirFunctions = UniversalPipelineTestUtils.createFHIRFunctionsInstance()

        // execute
        fhirFunctions.doReceiverFilter(queueMessage, 1, receiverFilter)

        // check queue message
        verify(exactly = 0) {
            QueueAccess.sendMessage(elrTranslationQueueName, any())
        }

        // check action table
        UniversalPipelineTestUtils.checkActionTable(listOf(TaskAction.receive, TaskAction.receiver_filter))

        // check results
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            // check for terminated lineage
            val routedReport = UniversalPipelineTestUtils.fetchChildReports(report, txn, 1).single()
            assertThat(routedReport.nextAction).isEqualTo(TaskAction.none)
            assertThat(routedReport.bodyUrl).isNull()
            assertThat(routedReport.schemaTopic).isEqualTo(Topic.FULL_ELR)
            assertThat(routedReport.bodyFormat).isEqualTo("FHIR")
            assertThat(routedReport.itemCount).isZero()

            // check filter logging
            val actionLogRecords = DSL.using(txn)
                .select(Tables.ACTION_LOG.asterisk())
                .from(Tables.ACTION_LOG)
                .fetchInto(ActionLog::class.java)

            assertThat(actionLogRecords).hasSameSizeAs(fullElrQualityFilterSample)

            actionLogRecords.forEachIndexed { index, actionLog ->
                assertThat(actionLog.trackingId).isEqualTo(validFHIRRecord1Identifier)
                assertThat(actionLog.detail).isInstanceOf<FHIRReceiverFilter.ReceiverItemFilteredActionLogDetail>()
                    .matchesPredicate {
                        it.filterType == ReportStreamFilterType.QUALITY_FILTER &&
                            it.filter == fullElrQualityFilterSample[index] &&
                            it.receiverName == receiver.name &&
                            it.receiverOrg == receiver.organizationName
                    }
            }
        }

        // check events
        assertThat(azureEventService.events).hasSize(1)
        val bundle = FhirTranscoder.decode(reportContents)
        assertThat(azureEventService.events.single()).isInstanceOf<ReceiverFilterFailedEvent>()
            .isEqualToIgnoringGivenProperties(
                ReceiverFilterFailedEvent(
                    UUID.randomUUID(), // ignored
                    report.id,
                    report.id,
                    Topic.FULL_ELR,
                    "phd.Test Sender",
                    receiver.fullName,
                    AzureEventUtils.getObservationSummaries(bundle),
                    fullElrQualityFilterSample,
                    ReportStreamFilterType.QUALITY_FILTER,
                    reportContents.length,
                    AzureEventUtils.getIdentifier(bundle)
                ),
                ReceiverFilterFailedEvent::reportId
            )
    }

    @Test
    fun `should respect simple quality filter and send message`() {
        // set up
        val receiverSetupData = listOf(
            UniversalPipelineTestUtils.ReceiverSetupData(
                "x",
                jurisdictionalFilter = listOf("true"),
                routingFilter = listOf("true"),
                qualityFilter = simpleElrQualifyFilter
            )
        )
        val receivers = UniversalPipelineTestUtils.createReceivers(receiverSetupData)
        val receiver = receivers.first()
        val org = UniversalPipelineTestUtils.createOrganizationWithReceivers(receivers)
        val receiverFilter = createReceiverFilter(azureEventService, org)
        val reportContents = File(VALID_FHIR_URL).readText()
        val report = UniversalPipelineTestUtils.createReport(
            reportContents,
            TaskAction.receiver_filter,
            Event.EventAction.RECEIVER_FILTER,
            azuriteContainer
        )
        val queueMessage = generateQueueMessage(
            report,
            reportContents,
            UniversalPipelineTestUtils.fhirSenderWithNoTransform,
            receiver.fullName
        )
        val fhirFunctions = UniversalPipelineTestUtils.createFHIRFunctionsInstance()

        // execute
        fhirFunctions.doReceiverFilter(queueMessage, 1, receiverFilter)

        // check results
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routedReport = UniversalPipelineTestUtils.fetchChildReports(report, txn, 1).single()
            val routedContents = String(
                BlobAccess.downloadBlobAsByteArray(
                routedReport.bodyUrl,
                UniversalPipelineTestUtils.getBlobContainerMetadata(azuriteContainer)
            )
            )
            assertThat(routedContents).isEqualTo(reportContents)

            // check queue message
            val expectedRouteQueueMessage = FhirTranslateQueueMessage(
                routedReport.reportId,
                routedReport.bodyUrl,
                BlobAccess.digestToString(routedReport.blobDigest),
                "phd.fhir-elr-no-transform",
               Topic.FULL_ELR,
                receiver.fullName
            ).serialize()

            verify(exactly = 1) {
                QueueAccess.sendMessage(
                    elrTranslationQueueName,
                    expectedRouteQueueMessage
                )
            }

            // check events
            assertThat(azureEventService.events).hasSize(1)
            val bundle = FhirTranscoder.decode(reportContents)
            assertThat(azureEventService.events.single())
                .isInstanceOf<ReportRouteEvent>()
                .isEqualTo(
                    ReportRouteEvent(
                        routedReport.reportId,
                        report.id,
                        report.id,
                        Topic.FULL_ELR,
                        "phd.Test Sender",
                        receiver.fullName,
                        AzureEventUtils.getObservationSummaries(bundle),
                        emptyList(),
                        reportContents.length,
                        AzureEventUtils.getIdentifier(bundle)
                    )
                )

            // check action table
            UniversalPipelineTestUtils.checkActionTable(listOf(TaskAction.receive, TaskAction.receiver_filter))
        }
    }

    @Test
    fun `should respect processing mode filter and send message`() {
        // set up
        val receiverSetupData = listOf(
            UniversalPipelineTestUtils.ReceiverSetupData(
                "x",
                jurisdictionalFilter = listOf("true"),
                qualityFilter = listOf("true"),
                routingFilter = listOf("true"),
                processingModeFilter = processingModeFilterProduction
            )
        )
        val receivers = UniversalPipelineTestUtils.createReceivers(receiverSetupData)
        val receiver = receivers.single()
        val org = UniversalPipelineTestUtils.createOrganizationWithReceivers(receivers)
        val receiverFilter = createReceiverFilter(azureEventService, org)
        val reportContents = File(VALID_FHIR_URL).readText()
        val report = UniversalPipelineTestUtils.createReport(
            reportContents,
            TaskAction.receiver_filter,
            Event.EventAction.RECEIVER_FILTER,
            azuriteContainer
        )
        val queueMessage = generateQueueMessage(
            report,
            reportContents,
            UniversalPipelineTestUtils.fhirSenderWithNoTransform,
            receiver.fullName
        )
        val fhirFunctions = UniversalPipelineTestUtils.createFHIRFunctionsInstance()

        // execute
        fhirFunctions.doReceiverFilter(queueMessage, 1, receiverFilter)

        // check results
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routedReport = UniversalPipelineTestUtils.fetchChildReports(report, txn, 1).single()
            val routedBundle = BlobAccess.downloadBlobAsByteArray(
                routedReport.bodyUrl,
                UniversalPipelineTestUtils.getBlobContainerMetadata(azuriteContainer)
            )
            assertThat(String(routedBundle)).isEqualTo(reportContents)

            // check queue message
            val expectedRouteQueueMessage = FhirTranslateQueueMessage(
                routedReport.reportId,
                routedReport.bodyUrl,
                BlobAccess.digestToString(routedReport.blobDigest),
                "phd.fhir-elr-no-transform",
               Topic.FULL_ELR,
                "phd.x"
            ).serialize()

            verify(exactly = 1) {
                QueueAccess.sendMessage(elrTranslationQueueName, expectedRouteQueueMessage)
            }

            // check events
            assertThat(azureEventService.events).hasSize(1)
            val bundle = FhirTranscoder.decode(reportContents)
            assertThat(azureEventService.events.single())
                .isInstanceOf<ReportRouteEvent>()
                .isEqualTo(
                    ReportRouteEvent(
                        routedReport.reportId,
                        report.id,
                        report.id,
                        Topic.FULL_ELR,
                        "phd.Test Sender",
                        receiver.fullName,
                        AzureEventUtils.getObservationSummaries(bundle),
                        emptyList(),
                        reportContents.length,
                        AzureEventUtils.getIdentifier(bundle)
                    )
                )

            // check action table
            UniversalPipelineTestUtils.checkActionTable(listOf(TaskAction.receive, TaskAction.receiver_filter))
        }
    }

    @Test
    fun `should respect processing mode filter and not send message`() {
        // set up
        val receiverSetupData = listOf(
            UniversalPipelineTestUtils.ReceiverSetupData(
                "x",
                jurisdictionalFilter = listOf("true"),
                qualityFilter = listOf("true"),
                routingFilter = listOf("true"),
                processingModeFilter = processingModeFilterDebugging,
            )
        )

        val receivers = UniversalPipelineTestUtils.createReceivers(receiverSetupData)
        val receiver = receivers.single()
        val org = UniversalPipelineTestUtils.createOrganizationWithReceivers(receivers)
        val receiverFilter = createReceiverFilter(azureEventService, org)
        val reportContents = File(VALID_FHIR_URL).readText()
        val report = UniversalPipelineTestUtils.createReport(
            reportContents,
            TaskAction.receiver_filter,
            Event.EventAction.RECEIVER_FILTER,
            azuriteContainer
        )
        val queueMessage = generateQueueMessage(
            report,
            reportContents,
            UniversalPipelineTestUtils.fhirSenderWithNoTransform,
            receiver.fullName
        )
        val fhirFunctions = UniversalPipelineTestUtils.createFHIRFunctionsInstance()

        // execute
        fhirFunctions.doReceiverFilter(queueMessage, 1, receiverFilter)

        // check queue
        verify(exactly = 0) {
            QueueAccess.sendMessage(elrTranslationQueueName, any())
        }

        // check action table
        UniversalPipelineTestUtils.checkActionTable(listOf(TaskAction.receive, TaskAction.receiver_filter))

        // check results
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            // check for terminated lineage
            val routedReport = UniversalPipelineTestUtils.fetchChildReports(report, txn, 1).single()
            assertThat(routedReport.nextAction).isEqualTo(TaskAction.none)
            assertThat(routedReport.bodyUrl).isNull()
            assertThat(routedReport.schemaTopic).isEqualTo(Topic.FULL_ELR)
            assertThat(routedReport.bodyFormat).isEqualTo("FHIR")
            assertThat(routedReport.itemCount).isZero()

            // check filter logging
            val actionLogRecords = DSL.using(txn)
                .select(Tables.ACTION_LOG.asterisk())
                .from(Tables.ACTION_LOG)
                .fetchInto(ActionLog::class.java)

            assertThat(actionLogRecords).hasSize(1)

            with(actionLogRecords.single()) {
                assertThat(this.trackingId).isEqualTo("MT_COCNB_ORU_NBPHELR.1.5348467")
                assertThat(this.type).isEqualTo(ActionLogLevel.warning)
                assertThat(this.scope).isEqualTo(ActionLogScope.item)
                assertThat(this.index).isEqualTo(1)
                assertThat(this.detail).isInstanceOf<FHIRReceiverFilter.ReceiverItemFilteredActionLogDetail>()
                    .matchesPredicate {
                        it.filter == processingModeFilterDebugging.single() &&
                        it.filterType == ReportStreamFilterType.PROCESSING_MODE_FILTER &&
                        it.receiverName == receiver.name &&
                        it.receiverOrg == receiver.organizationName
                    }
            }
        }

        // check events
        assertThat(azureEventService.events).hasSize(1)
        val bundle = FhirTranscoder.decode(reportContents)
        assertThat(azureEventService.events.single()).isInstanceOf<ReceiverFilterFailedEvent>()
            .isEqualToIgnoringGivenProperties(
                ReceiverFilterFailedEvent(
                    UUID.randomUUID(), // ignored
                    report.id,
                    report.id,
                    Topic.FULL_ELR,
                    "phd.Test Sender",
                    receiver.fullName,
                    AzureEventUtils.getObservationSummaries(bundle),
                    processingModeFilterDebugging,
                    ReportStreamFilterType.PROCESSING_MODE_FILTER,
                    reportContents.length,
                    AzureEventUtils.getIdentifier(bundle)
                ),
                ReceiverFilterFailedEvent::reportId
            )
    }
}