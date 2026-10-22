package com.openthinking.blankassistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

fun isDefaultAssistant(context: Context): Boolean =
    context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

/**
 * The system's "set as default digital assistant" dialog. Android only lets the user make the
 * switch, so this is as close to "set it for me" as an app gets; falls back to the settings page.
 */
fun assistantRoleIntent(context: Context): Intent {
    val roles = context.getSystemService(RoleManager::class.java)
    return if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
        roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    } else {
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
}
