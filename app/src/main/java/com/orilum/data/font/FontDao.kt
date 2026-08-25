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

    /** 按 id 取单个（阻塞式，供 WebView 同步字体加载线程直调）。 */
    @Query("SELECT * FROM font_faces WHERE id = :id")
    fun byIdBlocking(id: Long): FontFace?

    /** 导入以 (familyName, subfamily) 为准去重（同款同字重重导覆盖），并带唯一索引兜底。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fonts: List<FontFace>)

    /** 删除单个已导入字体（用户主动删除）。阻塞式，供同步字体加载线程直调。 */
    @Query("DELETE FROM font_faces WHERE id = :id")
    fun delete(id: Long)

    /** 删除一个家族的全部字重（显示层按家族合并，左滑删除以家族为单位）。 */
    @Query("DELETE FROM font_faces WHERE familyName = :familyName")
    suspend fun deleteByFamily(familyName: String)
}