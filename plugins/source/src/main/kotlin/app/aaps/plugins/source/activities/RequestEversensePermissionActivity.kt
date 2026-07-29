package app.aaps.plugins.source.activities

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import app.aaps.plugins.source.EversensePlugin
import app.aaps.plugins.source.R
import dagger.android.support.DaggerAppCompatActivity

class RequestEversensePermissionActivity : DaggerAppCompatActivity() {

    private val requestCode = "AndroidAPS BYOESA".sumOf { it.code }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.permission_byoesa_title)
            .setMessage(R.string.permission_byoesa_description)
            .setPositiveButton(R.string.permission_byoesa_allow) { _, _ ->
                requestPermissions(arrayOf(EversensePlugin.PERMISSION), requestCode)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
