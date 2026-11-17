package com.example.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactMatch(
    val displayName: String,
    val phoneNumber: String
)

class ContactsResolver(private val context: Context) {

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    fun findContactByName(query: String): ContactMatch? {
        if (!hasContactsPermission()) return null
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return null

        val matches = mutableListOf<ContactMatch>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                    val num = if (numIdx >= 0) cursor.getString(numIdx).orEmpty().trim() else ""
                    if (name.isNotEmpty() && num.isNotEmpty()) {
                        matches.add(ContactMatch(displayName = name, phoneNumber = num))
                    }
                }
            }
        }

        return matches.firstOrNull { it.displayName.equals(cleanQuery, ignoreCase = true) }
            ?: matches.firstOrNull { it.displayName.lowercase().startsWith(cleanQuery) }
            ?: matches.firstOrNull { it.displayName.lowercase().contains(cleanQuery) }
    }
}
