package gov.cdc.prime.router.serializers.datatests.hl7

import ca.uhn.hl7v2.DefaultHapiContext
import ca.uhn.hl7v2.model.v251.message.ORU_R01
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory
import ca.uhn.hl7v2.parser.PipeParser
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import gov.cdc.prime.router.Metadata
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.TestSource
import gov.cdc.prime.router.serializers.Hl7Serializer
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.filefilter.SuffixFileFilter
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.function.Executable
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs data comparison tests for HL7 ORU R01 messages.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObservationMessageTests {

    /**
     * The folder from the classpath for the test files
     */
    private val TEST_FILE_DIR = "/test_data_files/hl7"

    /**
     * Generate individual unit tests for each test file in the test folder.
     * @return a list of dynamic unit tests
     */
    @TestFactory
    fun generateDataTests(): Collection<DynamicTest> {
        var dynamicTests = ArrayList<DynamicTest>()

        val testFiles = getTestFiles(TEST_FILE_DIR)
        testFiles.forEach {
            dynamicTests.add(DynamicTest.dynamicTest("Test ${FilenameUtils.getBaseName(it)}", FileTest(it)))
        }
        println("Testing ${testFiles.size} files ...")
        return dynamicTests
    }

    /**
     * Gets a list of test files from the given [path].
     * @return a list of absolute pathnames to the test files
     */
    private fun getTestFiles(path: String): List<String> {
        var files = ArrayList<String>()
        val fullDirPath = this.javaClass.getResource(path).path
        val dir = File(fullDirPath)
        if(dir.exists()) {
            val filenames = dir.list(SuffixFileFilter(".hl7"))
            filenames.forEach { files.add("$fullDirPath/$it") }
            if(files.isEmpty()) fail("There are no HL7 files present in $fullDirPath")
        } else {
            fail("Directory $path does not exist in the classpath.")
        }
        files.forEach { println(it) }
        return files
    }

    /**
     * Perform the unit test for the given HL7 [hl7AbsolutePath].  This test will compare the number of provided reports
     * (e.g. HL7 batch files have multiple reports) and verifies all data elements match the expected values in the
     * related .internal file that exists in the same folder as the HL7 test file.
     */
    class FileTest(val hl7AbsolutePath: String): Executable {
        /**
         * The schema to use.
         */
        private val SCHEMA_NAME = "covid-19"

        /**
         * The HAPI HL7 parser.
         */
        private val parser: PipeParser

        /**
         * The HL7 serializer.
         */
        private val serializer: Hl7Serializer

        init {
            // Make sure we have some content on the given HL7 file
            assertTrue(File(hl7AbsolutePath).length() > 0)

            // Initialize the HL7 parser
            val hapiContext = DefaultHapiContext()
            val mcf = CanonicalModelClassFactory("2.5.1")
            hapiContext.modelClassFactory = mcf
            parser = hapiContext.pipeParser

            // Setup the HL7 serializer
            val metadata = Metadata("./metadata")
            serializer = Hl7Serializer(metadata)
        }

        override fun execute() {
            val expectedResultAbsolutePath = "${FilenameUtils.removeExtension(hl7AbsolutePath)}.internal"
            val testFilename = FilenameUtils.getName(hl7AbsolutePath)

            println("Testing file $testFilename ...")
            if(File(expectedResultAbsolutePath).exists()) {
                verifyMsgType()
                val report = getReport()
                val expectedResult = readExpectedResult(expectedResultAbsolutePath)
                compareToExpected(report, expectedResult)
                assertTrue(true)
            } else {
                fail("The expected data file $expectedResultAbsolutePath was not found for this test.")
            }
        }

        /**
         * Verify we have an ORU R01 HL7 report
         */
        private fun verifyMsgType() {
            // TODO SUPPORT BATCH HL7
            // A sanity check on the data before we start
            val hapiMsg = parser.parse(File(hl7AbsolutePath).readText())
            val oruMsg = hapiMsg as ORU_R01
            val msh = oruMsg.getMSH()
            assertEquals("ORU", msh.messageType.messageCode.toString())
            assertEquals("R01", msh.messageType.triggerEvent.toString())
        }

        /**
         * Get the report for the HL7 file and check for errors.
         * @return the HL7 report
         */
        private fun getReport(): Report {
            val result = serializer.readExternal(SCHEMA_NAME, File(hl7AbsolutePath).inputStream(), TestSource)
            val filename = FilenameUtils.getName(hl7AbsolutePath)
            assertNotNull(result)
            assertNotNull(result.report)

            if(result.errors.isNotEmpty()) {
                println("HL7 file $filename has ${result.errors.size} errors:")
                result.errors.forEach {println("   ERROR: ${it.details}")}
            }
            if(result.warnings.isNotEmpty()) {
                println("HL7 file $filename has ${result.warnings.size} warnings:")
                result.warnings.forEach {println("   WARNING: ${it.details}")}
            }
            assertTrue(result.errors.isEmpty(), "There were data errors in the HL7 file.")
            return result.report!!
        }

        /**
         * Read the file [expectedFileAbsolutePath] that has the expected result.
         * @return a list of data rows
         */
        private fun readExpectedResult(expectedFileAbsolutePath: String): List<List<String>> {
            val file = File(expectedFileAbsolutePath)
            return csvReader().readAll(file)
        }

        /**
         * Compare the data in the [actual] report to the data in the [expected] report.
         */
        private fun compareToExpected(actual: Report, expected: List<List<String>>) {
            assertTrue(actual.schema.elements.isNotEmpty())

            // Is there a header row in the expected file?
            var expectedHasHeader = false
            var expectedSize = expected.size
            val firstColName = actual.schema.elements[0].name
            if(expected.size > 0 && expected[0][0] == firstColName) {
                expectedHasHeader = true
                expectedSize--
            }

            // Check the number of reports
            assertEquals(actual.itemCount, expectedSize,"Number of reports does not match.")

            // Now check the data in each report.
            for(i in 0 until actual.itemCount) {
                val actualRow = actual.getRow(i)
                val expectedRowIndex = if(expectedHasHeader) i + 1 else i
                val expectedRow = expected[expectedRowIndex]
                assertEquals(actualRow.size, expectedRow.size,"Incorrect number of columns in data for report #$i.")
                for(j in actualRow.indices) {
                    val colName = actual.schema.elements[j].name
                    assertEquals(actualRow[j].trim(), expectedRow[j].trim(),
                        "Data value does not match for in report $i column #$j, '$colName'.")
                }
            }

        }
    }

}