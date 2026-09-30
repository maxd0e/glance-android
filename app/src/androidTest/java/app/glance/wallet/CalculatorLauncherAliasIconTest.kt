package app.glance.wallet

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

class CalculatorLauncherAliasIconTest {
    @Test fun calculatorAliasUsesDedicatedLauncherResources() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageManager = context.packageManager
        val alias = ComponentName(context, "${context.packageName}.CalculatorLauncherAlias")
        val activityInfo = packageManager.getActivityInfo(
            alias,
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val launcherIcon = context.resources.getIdentifier(
            "ic_calculator_launcher",
            "mipmap",
            context.packageName,
        )
        val roundIcon = context.resources.getIdentifier(
            "ic_calculator_launcher_round",
            "mipmap",
            context.packageName,
        )

        assertNotEquals("Calculator launcher mipmap asset must exist", 0, launcherIcon)
        assertNotEquals("Calculator round launcher mipmap asset must exist", 0, roundIcon)
        assertEquals(launcherIcon, activityInfo.icon)
    }

    @Test fun calculatorArtworkIsScaledToEightyFivePercentAroundItsCenter() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parser = context.resources.getXml(R.drawable.ic_calculator_launcher_foreground)

        while (parser.eventType != XmlPullParser.START_TAG) parser.next()
        parser.nextTag()

        assertEquals("group", parser.name)
        assertEquals(0.60f, parser.getAttributeFloatValue(ANDROID_NAMESPACE, "scaleX", Float.NaN))
        assertEquals(0.60f, parser.getAttributeFloatValue(ANDROID_NAMESPACE, "scaleY", Float.NaN))
        assertEquals(60f, parser.getAttributeFloatValue(ANDROID_NAMESPACE, "pivotX", Float.NaN))
        assertEquals(60f, parser.getAttributeFloatValue(ANDROID_NAMESPACE, "pivotY", Float.NaN))
        assertTrue(parser.depth > 1)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
