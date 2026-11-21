package io.github.sebbejohansson.yggdrasil.home

import android.content.Intent
import io.github.sebbejohansson.yggdrasil.MainActivity

/**
 * Ygg as the phone's home screen: the same app, opening on the Apps page.
 * Home always comes back here, and each press clears the search and returns
 * to the apps. Gemma isn't loaded for it: the model loads on your first
 * question, as in the app.
 */
class HomeActivity : MainActivity() {
    override val home = true

    // Public so a test can press Home.
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) homePresses.value += 1
    }
}
