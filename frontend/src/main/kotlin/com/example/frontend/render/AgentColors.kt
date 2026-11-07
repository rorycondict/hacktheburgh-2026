package com.example.frontend.render

import java.awt.Color

/** Per-agent tunic colours, indexed by a stable colour slot. Slot 0 is the authored blue. */
object AgentColors {
    val tints: List<Tint> = listOf(
        Tint(0x3d72d6, 0x26498f), // blue
        Tint(0xd64545, 0x8f2626), // red
        Tint(0x3fa356, 0x226b37), // green
        Tint(0x8a5ad6, 0x55348f), // purple
        Tint(0xe0892e, 0x995516), // orange
        Tint(0x2fb3a8, 0x1b7068), // teal
        Tint(0xe05a9e, 0x992e66), // pink
        Tint(0xd9b42e, 0x8f7414), // yellow
    )

    fun tint(slot: Int): Tint = tints[Math.floorMod(slot, tints.size)]
    fun color(slot: Int): Color = Color(tint(slot).main)
}
