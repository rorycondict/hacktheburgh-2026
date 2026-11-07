package com.example.frontend.hud

import java.util.Locale

internal object Format {
    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.ROOT, "%.2fM", n / 1_000_000.0)
        n >= 10_000 -> String.format(Locale.ROOT, "%.0fk", n / 1_000.0)
        n >= 1_000 -> String.format(Locale.ROOT, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    fun money(amount: Double, currency: String): String {
        val digits = if (amount != 0.0 && amount < 0.1) 3 else 2
        val num = String.format(Locale.ROOT, "%.${digits}f", amount)
        return if (currency.equals("USD", ignoreCase = true)) "$$num" else "$num $currency"
    }
}
