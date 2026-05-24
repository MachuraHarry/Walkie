package com.ronin.walkie.model

data class Channel(
    val id: Int = 0,
    val name: String = "",
    val description: String = "",
    val color: String = "#4CAF50",
    val created_by: String = "",
    val created_at: String = "",
    val is_active: Boolean = true,
    val member_count: Int = 0
)
