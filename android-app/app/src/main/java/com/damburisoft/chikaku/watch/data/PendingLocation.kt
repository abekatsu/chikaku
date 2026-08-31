package com.damburisoft.chikaku.watch.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 送信待ちの位置情報。圏外・サーバー停止中でも失わないよう、測位したものは
 * まずこのテーブルに書き、送信成功時に削除する（CLAUDE.md §2.4）。
 */
@Entity(tableName = "pending_locations")
data class PendingLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    /** 測位時刻（epoch millis, UTC） */
    val recordedAt: Long,
    val batteryLevel: Int,
    /**
     * 測位の出どころを判断するための生の値 (Issue #13)。
     * **null は「該当しない」ではなく「この列より前のアプリが積んだ」。**
     * 更新をまたいでキューに残った行を、誤って NETWORK と決めつけないため。
     */
    val provider: String? = null,
    val hasAltitude: Boolean? = null,
    val hasSpeed: Boolean? = null,
    val hasBearing: Boolean? = null,
    /** 送信を試みて失敗した回数。増えすぎたものは捨てる。 */
    val attempts: Int = 0,
)

@Dao
interface PendingLocationDao {

    @Insert
    suspend fun insert(location: PendingLocation): Long

    @Query("SELECT * FROM pending_locations ORDER BY recordedAt ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingLocation>

    @Query("DELETE FROM pending_locations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_locations SET attempts = attempts + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: Long)

    @Query("SELECT COUNT(*) FROM pending_locations")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_locations")
    suspend fun count(): Int

    @Query("DELETE FROM pending_locations")
    suspend fun clear()

    /**
     * 溜まりすぎたキューを間引く。長期間圏外だった場合に無限に膨らむのを防ぐ。
     * 新しいものを [keep] 件だけ残す。
     */
    @Query(
        "DELETE FROM pending_locations WHERE id NOT IN " +
            "(SELECT id FROM pending_locations ORDER BY recordedAt DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)
}
