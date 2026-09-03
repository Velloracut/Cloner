package com.vellora.dualapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloned_apps")
data class ClonedAppEntity(
    @PrimaryKey val id: Long,
    val label: String,
    val packageName: String
)
