package moe.reimu.mua

import android.content.Context
import androidx.annotation.StringRes

/** Keep resource IDs in retained state so text follows the current locale. */
internal data class UiText(@param:StringRes val resource: Int, val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String = context.getString(resource, *args.toTypedArray())
}
