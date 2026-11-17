package com.yboard.keyboard.ui

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.YboardSettings

/** Typing → "Email & passwords": the email keys, your saved address, and autofill chips. */
@Composable
fun EmailAndPasswordsSection(settings: YboardSettings) {
    var email by remember { mutableStateOf(settings.myEmail) }
    val looksWrong = email.isNotBlank() && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    Section("Email & passwords") {
        SwitchRow(
            "Email helpers",
            "In email boxes: @ and .com keys, plus @gmail.com and your address in the strip",
            settings.emailHelpers,
        ) { b -> Prefs.update { it.copy(emailHelpers = b) } }
        OutlinedTextField(
            value = email,
            onValueChange = { v ->
                email = v.replace(" ", "").take(80)
                Prefs.update { it.copy(myEmail = email.trim()) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            enabled = settings.emailHelpers,
            isError = looksWrong,
            label = { Text("Your email") },
            placeholder = { Text("name@gmail.com") },
            supportingText = {
                Text(
                    if (looksWrong) "That doesn't look like a full email yet"
                    else "Shows in the strip in email boxes, so you can type it in one tap. Stays on this phone."
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        )
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        SwitchRow(
            "Passwords & codes",
            if (supported) "Logins from your password manager and codes from texts show in the strip, like in Gboard"
            else "Needs Android 11 or newer",
            settings.autofillChips, enabled = supported,
        ) { b -> Prefs.update { it.copy(autofillChips = b) } }
        if (supported && settings.autofillChips) {
            HintText(
                "These come from your phone's autofill service (like Google or your password manager). Yboard only shows " +
                    "them and never sees your passwords. If nothing shows up, check Android Settings → Passwords & accounts " +
                    "→ Autofill service."
            )
        }
    }
}
