package io.github.mgelsinger.smsbridge.platform

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/**
 * A link between this app and a companion device (the PC, over Bluetooth) through
 * Android's CompanionDeviceManager. Android 17 holds verification-code texts back from
 * apps for three hours, and lists "connected device companion apps" among those it
 * exempts without saying what qualifies; an app holding such an association is the
 * likeliest reading, and one other project saw it work on a Pixel. The link is made in
 * the system's own picker, which the user approves; this app scans nothing itself and
 * needs no Bluetooth permission.
 */
object CompanionLink {
    data class Linked(val id: Int, val label: String)

    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    /** The links this app holds now. */
    fun links(context: Context): List<Linked> {
        val cdm = manager(context) ?: return emptyList()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.map { Linked(it.id, label(it)) }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.mapIndexed { i, mac -> Linked(-1 - i, "Bluetooth device $mac") }
            }
        }.getOrDefault(emptyList())
    }

    private fun label(a: AssociationInfo): String =
        a.displayName?.toString()?.takeIf { it.isNotBlank() }
            ?: a.deviceMacAddress?.let { "Bluetooth device ${it.toString().uppercase()}" }
            ?: "A companion device"

    /**
     * Asks the system to show its picker of nearby Bluetooth devices. [onChooser] gets the
     * picker to launch from an activity; [onLinked] runs when the user has chosen one.
     */
    fun request(context: Context, onChooser: (IntentSender) -> Unit, onLinked: () -> Unit, onFailed: (String) -> Unit) {
        val cdm = manager(context) ?: return onFailed("This phone has no companion device support.")
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        if (Build.VERSION.SDK_INT >= 33) {
            val main = Executor { Handler(Looper.getMainLooper()).post(it) }
            cdm.associate(
                request,
                main,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)
                    override fun onAssociationCreated(associationInfo: AssociationInfo) = onLinked()
                    override fun onFailure(error: CharSequence?) = onFailed(error?.toString() ?: "The link was not made.")
                },
            )
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    @Deprecated("Replaced by onAssociationPending on newer Android")
                    override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)
                    override fun onFailure(error: CharSequence?) = onFailed(error?.toString() ?: "The link was not made.")
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    fun unlink(context: Context, link: Linked) {
        val cdm = manager(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 && link.id >= 0) {
                cdm.disassociate(link.id)
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.getOrNull(-1 - link.id)?.let { cdm.disassociate(it) }
            }
        }
    }
}
