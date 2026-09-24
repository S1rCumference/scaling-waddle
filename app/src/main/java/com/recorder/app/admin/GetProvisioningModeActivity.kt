package com.recorder.app.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/**
 * Answers `android.app.action.GET_PROVISIONING_MODE`, one of two activities Android 12+
 * requires a device admin app to implement for QR-code (or NFC) device-owner provisioning.
 *
 * Without this — and [PolicyComplianceActivity] for the other required action — provisioning
 * fails with a generic "Something went wrong, contact your IT admin" before
 * [RecorderDeviceAdminReceiver] ever runs, regardless of anything else about the QR code
 * being correct. See DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE's documentation:
 * "From version Q, the admin app can choose whether to set up a fully managed device or a
 * managed profile. For the admin app to support this, it must have an activity with intent
 * filter ACTION_GET_PROVISIONING_MODE and another one with intent filter
 * ACTION_ADMIN_POLICY_COMPLIANCE" — and from S onward, omitting them fails provisioning
 * outright rather than merely falling back.
 *
 * This app only ever wants full device ownership, never a work profile — the phone this runs
 * on has no other user to protect it from — so the answer is fixed rather than asked of
 * anyone.
 */
class GetProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(
            RESULT_OK,
            Intent().putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            ),
        )
        finish()
    }
}
