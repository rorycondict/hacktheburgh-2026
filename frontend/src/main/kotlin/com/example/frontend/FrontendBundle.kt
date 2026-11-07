package com.example.frontend

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.FrontendBundle"

/** UI strings for the frontend. The product display name lives here only (`product.name`). */
internal object FrontendBundle : DynamicBundle(BUNDLE) {
    @Nls
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
