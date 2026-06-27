package com.hiktv.viewer.util

import android.content.Context
import android.view.KeyEvent
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog

/**
 * Makes BACK behave sanely on a dialog that has text fields, on Android TV where the leanback
 * keyboard doesn't reliably swallow BACK itself (so the first BACK was killing the whole dialog
 * and the typed data with it).
 *
 * We consume BACK entirely and decide by focus:
 *  - a text field is focused → hide the keyboard and move focus to the OK button (dialog stays)
 *  - otherwise → dismiss the dialog
 * So one BACK leaves the keyboard/field, a second BACK closes the dialog.
 */
object DialogIme {

    fun attach(dialog: AlertDialog, context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP) {
                val focused = dialog.currentFocus
                if (focused is EditText) {
                    imm?.hideSoftInputFromWindow(focused.windowToken, 0)
                    focused.clearFocus()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
                } else {
                    dialog.dismiss()
                }
            }
            true   // consume both DOWN and UP so the default BACK-dismiss never fires
        }
    }
}
