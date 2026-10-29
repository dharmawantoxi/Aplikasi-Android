package com.dharmawantoxi.docscan.ui

import android.text.InputFilter
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dharmawantoxi.docscan.R

object DocumentDialogs {
    fun rename(activity: AppCompatActivity, current: String, onSave: (String) -> Unit) {
        val input = EditText(activity).apply {
            setSingleLine(true)
            setText(current)
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(100))
            hint = Language.text(activity, "Nama dokumen", "Document name")
        }
        val container = LinearLayout(activity).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 4), Ui.dp(context, 24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(Language.text(activity, "Ubah nama dokumen", "Rename document"))
            .setView(container).setNegativeButton(Language.text(activity, "Batal", "Cancel"), null)
            .setPositiveButton(Language.text(activity, "Simpan", "Save"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isBlank()) input.error = Language.text(activity, "Nama tidak boleh kosong", "Name cannot be empty")
                else { dialog.dismiss(); onSave(value) }
            }
        }
        dialog.show()
    }

    fun delete(activity: AppCompatActivity, title: String, onDelete: () -> Unit) {
        AlertDialog.Builder(activity).setTitle(Language.text(activity, "Hapus dokumen?", "Delete document?"))
            .setMessage(Language.text(activity, "“$title” dan seluruh halamannya akan dihapus dari perangkat. Tindakan ini tidak dapat dibatalkan.", "“$title” and all its pages will be deleted from this device. This cannot be undone."))
            .setNegativeButton(Language.text(activity, "Batal", "Cancel"), null)
            .setPositiveButton(Language.text(activity, "Hapus", "Delete")) { _, _ -> onDelete() }.show()
    }
}
