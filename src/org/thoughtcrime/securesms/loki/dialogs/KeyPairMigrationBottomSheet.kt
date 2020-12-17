package org.thoughtcrime.securesms.loki.dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.android.synthetic.main.fragment_key_pair_migration_bottom_sheet.*
import network.loki.messenger.R
import org.thoughtcrime.securesms.ApplicationContext

class KeyPairMigrationBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_key_pair_migration_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        upgradeNowButton.setOnClickListener { upgradeNow() }
        upgradeLaterButton.setOnClickListener { dismiss() }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        // Expand the bottom sheet by default
        dialog.setOnShowListener {
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            BottomSheetBehavior.from(bottomSheet!!).setState(BottomSheetBehavior.STATE_EXPANDED);
        }
        return dialog
    }

    private fun upgradeNow() {
        val applicationContext = requireContext().applicationContext as ApplicationContext
        dismiss()
        val dialog = AlertDialog.Builder(requireContext())
        dialog.setMessage("You’re upgrading to a new Session ID. This will give you improved privacy and security, but it will clear ALL app data. Contacts and conversations will be lost. Proceed?")
        dialog.setPositiveButton(R.string.yes) { _, _ ->
            applicationContext.clearAllData(true)
        }
        dialog.setNegativeButton(R.string.cancel) { _, _ ->
            // Do nothing
        }
        dialog.create().show()
    }
}