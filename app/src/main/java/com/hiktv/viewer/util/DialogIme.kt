package com.hiktv.viewer.util

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog

/**
 * Makes BACK close the on-screen keyboard first (not the whole dialog) when a dialog contains
 * text fields. On Android TV the leanback IME doesn't always swallow BACK itself, so without
 * this the first BACK press dismisses the popup and throws away what the user typed.
 */
object DialogIme {

    fun attach(dialog: AlertDialog, context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            // While the keyboard is up, consume BACK entirely and just hide the keyboard.
            if (!imm.isAcceptingText) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP) {
                val focused = dialog.currentFocus
                imm.hideSoftInputFromWindow(focused?.windowToken, 0)
                focused?.clearFocus()
                // Park focus on a button so re-opening the keyboard needs a deliberate OK.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            }
            true
        }
    }
}
