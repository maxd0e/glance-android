package app.glance.wallet

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupRulesTest {
    @Test fun `cloud and device transfer exclude all app data domains`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/data_extraction_rules.xml"))
        val required = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = document.getElementsByTagName(section)
            assertEquals(1, nodes.length)
            val exclusions = (0 until nodes.item(0).childNodes.length).mapNotNull { index ->
                val element = nodes.item(0).childNodes.item(index)
                if (element.nodeName == "exclude" && element.attributes?.getNamedItem("path")?.nodeValue == ".")
                    element.attributes.getNamedItem("domain")?.nodeValue else null
            }.toSet()
            assertEquals(required, exclusions)
        }
    }
}
