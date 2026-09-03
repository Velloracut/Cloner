package com.vellora.dualapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClonedAppDao {
    @Query("SELECT * FROM cloned_apps ORDER BY id DESC")
    fun getAll(): Flow<List<ClonedAppEntity>>

    @Insert
    suspend fun insert(entity: ClonedAppEntity)

    @Delete
    suspend fun delete(entity: ClonedAppEntity)
}
