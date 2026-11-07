package com.example.frontend.render

import java.awt.Color

/**
 * Per-agent tunic colours, indexed by a stable colour slot: soft, slightly desaturated pastels that sit
 * well in the warm [CozyPalette] while staying easy to tell apart. Each replaces the authored tunic keys
 * ([Sprites.BASE_TINT]) in the hero sprites; the villager's hair is recoloured per slot as well.
 */
object AgentColors {
    val tints: List<Tint> = listOf(
        Tint(0x7f9fc8, 0x5b769c, 0x8a5a3b, 0x66402a), // cornflower
        Tint(0xcf8fa6, 0x9f6479, 0xd8b46a, 0xa8844a), // dusty rose
        Tint(0x8fb47e, 0x637f56, 0xa0523a, 0x763a28), // sage
        Tint(0xa996cc, 0x7a6a9e, 0x4e3a34, 0x382822), // lavender
        Tint(0xe2a86f, 0xae7a48, 0x7a6658, 0x584a40), // apricot
        Tint(0x72b5ab, 0x4f8780, 0xc8743e, 0x965228), // seafoam
        Tint(0xd8c06e, 0xa48f4a, 0x4a3444, 0x32222e), // butter
        Tint(0x8a9bb0, 0x617082, 0xb88a4e, 0x8a6436), // slate
    )

    /** The human user's character: a warm terracotta that no agent slot uses. */
    val USER_TINT = Tint(0xd9714e, 0xa5502f, 0x5a3a2a, 0x40281c)

    fun tint(slot: Int): Tint = tints[Math.floorMod(slot, tints.size)]
    fun color(slot: Int): Color = Color(tint(slot).main)
    val userColor: Color get() = Color(USER_TINT.main)
}
