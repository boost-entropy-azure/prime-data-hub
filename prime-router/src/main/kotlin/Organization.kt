package gov.cdc.prime.router

/**
 * Organization represents a partner organization of the hub. It has a jurisdiction.
 */
open class Organization(
    val name: String,
    val description: String,
    val jurisdiction: Jurisdiction,
    val stateCode: String?,
    val countyName: String?,
) {
    enum class Jurisdiction {
        FEDERAL,
        STATE,
        COUNTY
    }

    /**
     * Validate the object and return null or an error message
     */
    fun consistencyErrorMessage(): String? {
        return when (jurisdiction) {
            Jurisdiction.FEDERAL -> {
                if (stateCode != null || countyName != null)
                    "stateCode or countyName not allowed for FEDERAL organizations"
                else null
            }
            Jurisdiction.STATE -> {
                if (stateCode == null || countyName != null)
                    "stateCode required for STATE organizations"
                else null
            }
            Jurisdiction.COUNTY -> {
                if (stateCode == null || countyName == null)
                    "stateCode and countyName required for COUNTY organizations"
                else null
            }
        }
    }
}

/**
 * Organization with senders and receivers.
 *
 * Useful to put all the information about an org in single object or file.
 */
class DeepOrganization(
    name: String,
    description: String,
    jurisdiction: Jurisdiction,
    stateCode: String?,
    countyName: String?,
    val senders: List<Sender> = emptyList(),
    val receivers: List<Receiver> = emptyList(),
) : Organization(name, description, jurisdiction, stateCode, countyName)