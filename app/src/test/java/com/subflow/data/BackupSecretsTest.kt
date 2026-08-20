package com.subflow.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The API keys are the only secret this app holds. A backup is the one feature whose
 * whole job is to copy data somewhere else, so it is the one place a secret leaks
 * without anybody doing anything wrong.
 *
 * Two independent paths carry data off the device and both are covered here:
 * Android's own auto-backup (rules XML + manifest wiring) and the user's export file.
 */
class BackupSecretsTest {

    // unit tests run with app/ as the working directory
    private fun res(name: String) = File("src/main/res/xml/$name")
    private val manifest = File("src/main/AndroidManifest.xml")

    private fun parse(file: File): Element {
        assertTrue("missing ${file.path}", file.exists())
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).documentElement
    }

    /** every <exclude> under [parent] (or the whole doc when parent is null) as domain to path */
    private fun excludes(root: Element, parentTag: String?): List<Pair<String, String>> {
        val scope: List<Element> = if (parentTag == null) listOf(root) else {
            val nodes = root.getElementsByTagName(parentTag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        return scope.flatMap { p ->
            val nodes = p.getElementsByTagName("exclude")
            (0 until nodes.length).map { i ->
                val e = nodes.item(i) as Element
                e.getAttribute("domain") to e.getAttribute("path")
            }
        }
    }

    // --- the user's own export file ---

    @Test
    fun `an exported backup carries no api key`() {
        val fields = BackupManager.settingsFields(
            theme = "sakura", targetLang = "tr", autoSave = true, haptics = false
        )
        val suspicious = fields.keys.filter {
            Regex("api|key|token|secret", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        assertEquals("backup must not carry secrets, found: $suspicious", emptyList<String>(), suspicious)
    }

    @Test
    fun `an exported backup still carries the settings it promises`() {
        val fields = BackupManager.settingsFields(
            theme = "sakura", targetLang = "de", autoSave = true, haptics = false
        )
        assertEquals("sakura", fields["theme"])
        assertEquals("de", fields["targetLang"])
        assertEquals(true, fields["autoSave"])
        assertEquals(false, fields["haptics"])
    }

    // --- android auto-backup ---

    @Test
    fun `the excluded prefs file is the one the keys actually live in`() {
        // one source of truth: renaming the prefs must not silently orphan the rule
        assertEquals("${ApiKeys.PREFS}.xml", ApiKeys.PREFS_FILE)
    }

    @Test
    fun `cloud auto-backup excludes the api key preferences`() {
        val found = excludes(parse(res("backup_rules.xml")), null)
        assertTrue(
            "backup_rules.xml must exclude sharedpref/${ApiKeys.PREFS_FILE}, found $found",
            found.contains("sharedpref" to ApiKeys.PREFS_FILE)
        )
    }

    @Test
    fun `both cloud backup and device transfer exclude the api key preferences`() {
        val root = parse(res("data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val found = excludes(root, section)
            assertTrue(
                "<$section> must exclude sharedpref/${ApiKeys.PREFS_FILE}, found $found",
                found.contains("sharedpref" to ApiKeys.PREFS_FILE)
            )
        }
    }

    @Test
    fun `the manifest wires both backup rule files`() {
        val text = manifest.readText()
        // fullBackupContent covers API 30, dataExtractionRules covers 31+. minSdk is 30,
        // so dropping either one leaves a live version of Android backing the keys up.
        assertTrue(
            "manifest must set android:fullBackupContent",
            text.contains("android:fullBackupContent=\"@xml/backup_rules\"")
        )
        assertTrue(
            "manifest must set android:dataExtractionRules",
            text.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
        )
    }
}
