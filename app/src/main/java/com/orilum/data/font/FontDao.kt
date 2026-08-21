package com.orilum.data.font

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FontDao {

    /** 全部字体（仅用户导入，[FontFace] 不再区分系统/导入）。 */
    @Query("SELECT * FROM font_faces")
    suspend fun all(): List<FontFace>

    @Query("SELECT * FROM font_faces WHERE id = :id")
    suspend fun byId(id: Long): FontFace?

    /** 导入以 familyName 为准去重（同款重导做覆盖），并带唯一索引兜底。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fonts: List<FontFace>)
}