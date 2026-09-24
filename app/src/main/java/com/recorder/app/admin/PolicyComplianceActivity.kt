package com.recorder.app.admin

import android.app.Activity
import android.os.Bundle

/**
 * Answers `android.app.action.ADMIN_POLICY_COMPLIANCE`, the second activity Android 12+
 * requires for QR-code device-owner provisioning — see [GetProvisioningModeActivity] for why
 * both exist.
 *
 * There is nothing to show or collect here: this app's provisioning.json carries no admin
 * extras bundle, and the real setup happens afterward in
 * [RecorderDeviceAdminReceiver.onProfileProvisioningComplete] and the app's own first-run
 * wizard. So it finishes immediately with success, which the platform's own documentation
 * describes as a normal, complete answer to this action.
 */
class PolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
