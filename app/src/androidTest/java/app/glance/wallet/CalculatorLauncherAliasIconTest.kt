package app.glance.wallet

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

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
}
