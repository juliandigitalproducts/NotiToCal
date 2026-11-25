package de.juliandigitalproducts.notitocal.capture

import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import de.juliandigitalproducts.notitocal.model.runSafely

object AntiVocaleUtils {
  const val PACKAGE_NAME = "com.antivocale.app"
  const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.antivocale.app"
  const val PLAY_STORE_MARKET_URI = "market://details?id=com.antivocale.app"
  const val GITHUB_URL = "https://github.com/RisorseArtificiali/anti-vocale"

  fun isInstalled(context: Context): Boolean =
    runSafely { context.packageManager.getPackageInfo(PACKAGE_NAME, 0) }.isSuccess

  fun openApp(context: Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
    if (launch != null) {
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (runSafely { context.startActivity(launch) }.isSuccess) return true
    }
    return openPlayStore(context)
  }

  fun openPlayStore(context: Context): Boolean {
    val market = Intent(Intent.ACTION_VIEW, PLAY_STORE_MARKET_URI.toUri()).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runSafely { context.startActivity(market) }.isSuccess) return true
    val web = Intent(Intent.ACTION_VIEW, PLAY_STORE_URL.toUri()).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runSafely { context.startActivity(web) }.isSuccess
  }

  fun openGitHub(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri()).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runSafely { context.startActivity(intent) }.isSuccess
  }
}
