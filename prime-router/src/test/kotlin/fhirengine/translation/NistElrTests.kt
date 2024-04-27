package gov.cdc.prime.router.fhirengine.translation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import ca.uhn.hl7v2.DefaultHapiContext
import ca.uhn.hl7v2.model.Message
import ca.uhn.hl7v2.util.Hl7InputStreamMessageIterator
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory
import fhirengine.utils.ReportStreamCanonicalModelClassFactory
import gov.cdc.prime.router.fhirengine.engine.encodePreserveEncodingChars
import kotlin.test.Test
import fhirengine.translation.hl7.structures.nistelr251.message.ORU_R01 as NIST_ELR_ORU_R01

class NistElrTests {
    @Test
    fun `test nist elr java class`() {
        val rawMessage = """
MSH|^~\&#|STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO|CDC Atlanta^11D0668319^CLIA|CDPH CA CALREDIE^2.16.840.1.114222.4.3.3.10.1.1^ISO|CDPH_CID^2.16.840.1.114222.4.1.2.14104^ISO|20230802180802-0400||ORU^R01^ORU_R01|3015894743_04608717_11184|T|2.5.1|||NE|NE|USA||||PHLabReport-NoAck^PHIN^2.16.840.1.113883.9.11^ISO
SFT|CDC^^^^^CDC&2.16.840.1.114222.4&ISO^XX^^^CDC CLIA|ELIMS V11|STARLIMS|Binary ID unknown
PID|1||test^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^PI~PID123^^^SPHL-000048&2.16.840.1.114222.4.1.10765&ISO^PI||Mega^HL7^MI^^^^L||0000|F|||^^^^^USA^H
PD1|C^Small Children Dependent^HL70223^M^Medical Supervision Required^HL70223^2.5.1^4^TEST^M^Medical Supervision Required^HL70223^2.5.1^8.44.235.1.113883.3.3~O^Other^HL70223^U^Unknown^HL70223^2.5.1^4^TEST^M^Medical Supervision Required^HL70223^2.5.1^8.44.235.1.113883.3.3|A^Alone^HL70220^F^Family^HL70220^2.5.1^4^TEST^F^Family^HL70220^2.5.1^8.44.235.1.113883.3.3|Ordering Facility^1234-5&TestText&LN&1234-5&TestAltText&LN&1&2&OriginalText^123^Check Digit^C1^Assigning Authority&2.1.4.1&ISO^MD^Hospital A&2.16.840.1.113883.9.11&ISO^NameRepCode^OrgIdentifier~Ordering Facility^1234-5&TestText&LN&1234-5&TestAltText&LN&1&2&OriginalText^123^Check Digit^C1^Assigning Authority&2.1.4.1&ISO^MD^Hospital A&2.16.840.1.113883.9.11&ISO^NameRepCode^OrgIdentifier||F^Full-time student^HL70231^N^Not a student^HL70231^2.5.1^4^TEST^N^Not a student^HL70231^2.5.1^8.44.235.1.113883.3.3|T^TEST^HL70295^P^Prod^HL70295^2.5.1^4^TEST^D^Debug^HL70295^2.5.1^8.44.235.1.113883.3.3|F^Yes, patient has a living will but it is not on file^HL70315^I^No, patient does not have a living will but information was provided^HL70315^2.5.1^4^TEST^U^Unknown^HL70315^2.5.1^8.44.235.1.113883.3.3|F^Yes, patient is a documented donor, but documentation is not on file^HL70316^I^No, patient is not a documented donor, but information was provided^HL70316^2.5.1^4^TEST^U^Unknown^HL70316^2.5.1^8.44.235.1.113883.3.3|N|18547545^^^NIST MPI&2.16.840.1.113883.3.72.5.30.2&ISO^MR^University H&2.16.840.1.113883.3.0&ISO~111111111^^^SSN&2.16.840.1.113883.4.1&ISO^SS^SSA&2.16.840.1.113883.3.184&ISO|F^Family only^HL70215^N^No Publicity^HL70215^2.5.1^4^TEST^U^Unknown^HL70215^2.5.1^8.44.235.1.113883.3.3|N|20230501102531-0400|1st Ordering Facility^1234-5&TestText&LN&1234-5&TestAltText&LN&1&2&OriginalText^123^Check Digit^C1^Assigning Authority&2.1.4.1&ISO^MD^Hospital A&2.16.840.1.113883.9.11&ISO^NameRepCode^1st OrgIdentifier~2nd Ordering Facility^1234-5&TestText&LN&1234-5&TestAltText&LN&1&2&OriginalText^123^Check Digit^C1^Assigning Authority&2.1.4.1&ISO^MD^Hospital A&2.16.840.1.113883.9.11&ISO^NameRepCode^2nd OrgIdentifier|DNR^Do not resuscitate^HL70435^N^No directive^HL70435^2.5.1^4^TEST^N^No directive^HL70435^2.5.1^8.44.235.1.113883.3.3~DNR^Do not resuscitate^HL70435^N^No directive^HL70435^2.5.1^4^TEST^N^No directive^HL70435^2.5.1^8.44.235.1.113883.3.3|A^Active^HL70441^I^Inactive^HL70441^2.5.1^4^TEST^O^Other^HL70441^2.5.1^8.44.235.1.113883.3.3|20230501102531-0400|20230501102531-0400|AUSA^Australian Army^HL70140^AUSFA^Australian Air Force^HL70140^2.5.1^4^TEST^AUSN^Australian Navy^HL70140^2.5.1^8.44.235.1.113883.3.3|E1... E9^Enlisted^HL70141^O1 ... O9^Officers^HL70141^2.5.1^4^TEST^W1 ... W4^Warrent Officers^HL70141^2.5.1^8.44.235.1.113883.3.3|ACT^Active duty^HL70142^DEC^Deceased^HL70142^2.5.1^4^TEST^RET^Retired^HL70142^2.5.1^8.44.235.1.113883.3.3|20230501102531-0400
NTE|1|L|Accession level coment.|RE^Remark^HL70364^^^^2.5.1^^^^^^^2.16.840.1.113883.12.364
NTE|2|L|SPHL Submitter: CDPH, Viral and Rickettsial Disease Laboratory, Submitter ID: SPHL-000048, Address: 850 Marina Bay Parkway Rm. E-361 Richmond, California 94804 United States, Email: VRDL.Mail@cdph.ca.gov, Submitter Patient ID: PID123, Submitter Alt Patient ID: AltPID1234, Submitter Specimen ID: Specimen123, Submitter Alt Specimen ID: AltSP1234|RE^Remark^HL70364^^^^2.5.1^^^^^^^2.16.840.1.113883.12.364
NK1|1|Organa^Leia|N^Next-of-Kin^HL70131||^^PH^^31^201^234567^^^^^+31201234567
NK1|2|Navarro^Liz|N^Next-of-Kin^HL70131||^^PH^^31^201^234567^^^^^+31201234567
PV1|1|||||^^^Hospital A&2.4.4.4&ISO^^^^^^Entity ID&NAME&UNI&ISO
PV2|||||||||||4|||||||||||Y
ORC|RE|Specimen123^SPHL-000048^2.16.840.1.114222.4.1.10765^ISO|3015894743_04608717^STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO||||||20230725|||SPHL-000098^CA-Placer County Public Health Laboratory^^^^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^^^^XX||^NET^Internet^MWaKabon@placer.ca.gov|||||||CDPH, Viral and Rickettsial Disease Laboratory^D^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^XX^^^SPHL-000048|850 Marina Bay Parkway^Rm. E-361^Richmond^CA^94804^USA^M|^WPN^Internet^VRDL.Mail@cdph.ca.gov|11475 C Avenue^^Auburn^CA^95603^USA^M
OBR|1|Specimen123^SPHL-000048^2.16.840.1.114222.4.1.10765^ISO|3015894743_04608717^STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO|68991-9^Epidemiologically Important Information^LN^^^^2.74^^^CDC-10515^Poxvirus Molecular Detection^L^^2.16.840.1.113883.6.1|||202307241524|||||||||SPHL-000098^CA-Placer County Public Health Laboratory^^^^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^^^^XX|^NET^Internet^MWaKabon@placer.ca.gov|||||202308021808-0400|||F
OBX|1|CWE|80383-3^Flu B^LN||260415000^Not detected^SCT|||N^Normal^HL70078^^^^2.7|||F||||11D1111111^CSV uploads^CLIA||^BD Veritor System for Rapid Detection of SARS-CoV-2 \T\ Flu A+B*^^^^^^^BD Veritor System for Rapid Detection of SARS-CoV-2 \T\ Flu A+B*||||||CSV uploads-11D1111111^L^^^^CLIA&2.16.840.1.113883.4.7&ISO^XX^^^11D1111111|123 Main St^^^CA^94553^USA
OBX|2|CWE|80382-5^Flu A^LN||260373001^Detected^SCT|||A^Abnormal^HL70078^^^^2.7|||F||||11D1111111^CSV uploads^CLIA||^BD Veritor System for Rapid Detection of SARS-CoV-2 \T\ Flu A+B*^^^^^^^BD Veritor System for Rapid Detection of SARS-CoV-2 \T\ Flu A+B*||||||CSV uploads-11D1111111^L^^^^CLIA&2.16.840.1.113883.4.7&ISO^XX^^^11D1111111|123 Main St^^^CA^94553^USA
SPM|1|Specimen123&SPHL-000048&2.16.840.1.114222.4.1.10765&ISO^3015894743&STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO||119297000^Blood specimen^SCT^BLD^Blood^L^0912017^Adobe_Code^Blood|255251009^Acute phase^SCT^A^Acute^L^09012017^Adobe_Code^Acute|69519002^Ethylenediaminetetraacetic acid^SCT^EDT^Ethylenediaminetetraacetic acid (EDTA)^L^09012017^Adobe_Code^Ethylenediaminetetraacetic acid (EDTA)|62931000284108^Vacuum^SCT^VC^Vacuum^L^09012017^Adobe_Code^Vacuum|53120007^Arm^SCT^ARM^Arm^L^09012017^Adobe_Code^Arm|7771000^Left^SCT^LFT^Left^L^09012017^Adobe_Code^Left|||||, Elution|||202307241524|20230727175220||||||59102007^Ice pack^SCT^^^^09012017^^Cold (Ice Pack)
OBX|1|SN|21612-7^Reported Patient Age!!!^LN^^^^2.61||^28|a^Year^UCUM^^^^2.1|||||F|||202307241524|11D0668319^Centers for Disease Control and Prevention^CLIA||||20230727183017||||Centers for Disease Control and Prevention^L^^^^CLIA&2.16.840.1.113883.4.7&ISO^XX^^^11D0668319|1600 Clifton Road^^Atlanta^GA^30329^USA^B
OBR|2|Specimen123^SPHL-000048^2.16.840.1.114222.4.1.10765^ISO|47_3015894743_04608717_1233^STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO|100383-9^MVPX DNA Spec Ql NAA+probe^LN^1233^Monkeypox generic^L^2.74^v unknown^^CDC-10515^Poxvirus Molecular Detection^L^^2.16.840.1.113883.6.1|||202307241524|||||||||SPHL-000098^CA-Placer County Public Health Laboratory^^^^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^^^^XX|^NET^Internet^MWaKabon@placer.ca.gov|||||202308021726-0400|||F
NTE|1|L|Accession level coment.|RE^Remark^HL70364^^^^2.5.1^^^^^^^2.16.840.1.113883.12.364
NTE|2|L|Test level comment.|RE^Remark^HL70364^^^^2.5.1^^^^^^^2.16.840.1.113883.12.364
OBX|1|CWE|100383-9^MVPX DNA Spec Ql NAA+probe^LN^3900^Monkeypox generic^L^2.74^v_unknown^Monkeypox generic|ZZYGNAUM-1|10828004^Positive^SCT^^^^09012018^^Positive||||||F|||202307241524|11D0668319^Centers for Disease Control and Prevention^CLIA^47^Poxvirus Laboratory/Poxvirus and Rabies Branch^L|NXQ0@cdc.gov^Anderson^Christopher|||20230727183017-0400||||Centers for Disease Control and Prevention^L^^^^CLIA&2.16.840.1.113883.4.7&ISO^XX^^^11D0668319|1600 Clifton Rd^^Atlanta^GA^30329^USA^B
NTE|1|L|Run level Comment|RE^Remark^HL70364^^^^2.5.1^^^^^^^2.16.840.1.113883.12.364
NTE|2|L|Result level Comment.|RE^Remark^HL70364^^^^2.5.1^^^^^^^2.16.840.1.113883.12.364
SPM|1|Specimen123&SPHL-000048&2.16.840.1.114222.4.1.10765&ISO^3015894743&STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO||119297000^Blood specimen^SCT^BLD^Blood^L^0912017^Adobe_Code^Blood|255251009^Acute phase^SCT^A^Acute^L^09012017^Adobe_Code^Acute|69519002^Ethylenediaminetetraacetic acid^SCT^EDT^Ethylenediaminetetraacetic acid (EDTA)^L^09012017^Adobe_Code^Ethylenediaminetetraacetic acid (EDTA)|62931000284108^Vacuum^SCT^VC^Vacuum^L^09012017^Adobe_Code^Vacuum|53120007^Arm^SCT^ARM^Arm^L^09012017^Adobe_Code^Arm|7771000^Left^SCT^LFT^Left^L^09012017^Adobe_Code^Left|||||, Elution|||202307241524|20230727175220||||||59102007^Ice pack^SCT^^^^09012017^^Cold (Ice Pack)
OBX|1|SN|21612-7^Reported Patient Age???^LN^^^^2.61||^28|a^Year^UCUM^^^^2.1|||||F|||202307241524|11D0668319^Centers for Disease Control and Prevention^CLIA||||20230727183017||||Centers for Disease Control and Prevention^L^^^^CLIA&2.16.840.1.113883.4.7&ISO^XX^^^11D0668319|1600 Clifton Road^^Atlanta^GA^30329^USA^B
    """.trimIndent()
        val messages: MutableList<Message> = mutableListOf()
        val validationContext = ValidationContextFactory.noValidation()
        val context = DefaultHapiContext(ReportStreamCanonicalModelClassFactory(NIST_ELR_ORU_R01::class.java))
        context.validationContext = validationContext

        val iterator = Hl7InputStreamMessageIterator(rawMessage.byteInputStream(), context)
        while (iterator.hasNext()) {
            messages.add(iterator.next())
        }

        assertThat(messages).isNotEmpty()
        assertThat(messages[0].encodePreserveEncodingChars()).isNotEmpty()
        assertThat(messages[0].encodePreserveEncodingChars().trimIndent()).isEqualTo(rawMessage)
    }
}