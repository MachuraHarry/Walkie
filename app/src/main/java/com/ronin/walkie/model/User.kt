package com.ronin.walkie.model

import java.io.Serializable

data class User(
    val id: Int = 0,
    val username: String = "",
    val joined_at: String = "",
    val last_active: String = ""
) : Serializable
