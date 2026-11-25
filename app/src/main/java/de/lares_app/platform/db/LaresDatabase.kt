/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform.db

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules", indices = [Index("groupId")])
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [de.lares_app.core.policy.RuleType] name. */
    val type: String,
    val target: String,
    val limitMinutesPerDay: Int?,
    val enabled: Boolean,
    val createdAtMillis: Long,
    /** [RuleGroupEntity.id], null for standalone rules. Members are deleted together with the group. */
    val groupId: Long? = null,
    /** [ScheduleEntity.id] that applies instead of the limit (since schema 2). */
    val scheduleId: Long? = null,
)

@Entity(tableName = "rule_groups")
data class RuleGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val limitMinutesPerDay: Int?,
    val enabled: Boolean,
    val createdAtMillis: Long,
    /** [ScheduleEntity.id] that applies instead of the limit (since schema 2). */
    val scheduleId: Long? = null,
)

/** A schedule (since schema 2); only deleted while no rule or group uses it. */
@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [de.lares_app.core.policy.WindowMode] name for the time outside the windows. */
    val outside: String,
    val outsideMinutes: Int,
    /** JSON list of [de.lares_app.core.policy.ScheduleWindow]. */
    val windowsJson: String,
    val createdAtMillis: Long,
)

/** Time used per schedule window budget (since schema 2), kept for a few days after the window. */
@Entity(tableName = "window_usage", indices = [Index("dateEpochDay")])
data class WindowUsageEntity(
    @PrimaryKey val key: String,
    val foregroundMillis: Long,
    /** Start date of the window instance (for cleaning up). */
    val dateEpochDay: Long,
)

/** Summed foreground time of all group members per usage period. */
@Entity(
    tableName = "group_usage",
    primaryKeys = ["groupId", "periodStartMillis"],
    foreignKeys = [ForeignKey(entity = RuleGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("periodStartMillis")],
)
data class GroupUsageEntity(
    val groupId: Long,
    val periodStartMillis: Long,
    val foregroundMillis: Long,
)

/** Foreground time per rule and usage period (keyed by period start, see ResetSchedule). */
@Entity(
    tableName = "usage",
    primaryKeys = ["ruleId", "periodStartMillis"],
    foreignKeys = [ForeignKey(entity = RuleEntity::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("periodStartMillis")],
)
data class UsageEntity(
    val ruleId: Long,
    val periodStartMillis: Long,
    val foregroundMillis: Long,
)

@Entity(tableName = "tamper_events", indices = [Index("delivered")])
data class TamperEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [de.lares_app.core.data.TamperEventType] name. */
    val type: String,
    val timestampMillis: Long,
    val details: String?,
    val delivered: Boolean = false,
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY createdAtMillis")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY createdAtMillis")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: Long): RuleEntity?

    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage WHERE periodStartMillis = :periodStart")
    suspend fun period(periodStart: Long): List<UsageEntity>

    @Upsert
    suspend fun upsert(usage: UsageEntity)

    @Query("DELETE FROM usage WHERE periodStartMillis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM rule_groups ORDER BY createdAtMillis")
    fun observeAll(): Flow<List<RuleGroupEntity>>

    @Query("SELECT * FROM rule_groups ORDER BY createdAtMillis")
    suspend fun all(): List<RuleGroupEntity>

    @Query("SELECT * FROM rule_groups WHERE id = :id")
    suspend fun get(id: Long): RuleGroupEntity?

    @Insert
    suspend fun insert(group: RuleGroupEntity): Long

    @Update
    suspend fun update(group: RuleGroupEntity)

    @Query("DELETE FROM rules WHERE groupId = :id")
    suspend fun deleteMembers(id: Long)

    @Query("DELETE FROM rule_groups WHERE id = :id")
    suspend fun delete(id: Long)

    @Transaction
    suspend fun deleteWithMembers(id: Long) {
        deleteMembers(id)
        delete(id)
    }

    @Query("SELECT * FROM group_usage WHERE periodStartMillis = :periodStart")
    suspend fun usage(periodStart: Long): List<GroupUsageEntity>

    @Upsert
    suspend fun upsertUsage(usage: GroupUsageEntity)

    @Query("DELETE FROM group_usage WHERE periodStartMillis < :beforeMillis")
    suspend fun deleteUsageOlderThan(beforeMillis: Long)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun get(id: Long): ScheduleEntity?

    @Insert
    suspend fun insert(schedule: ScheduleEntity): Long

    @Update
    suspend fun update(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM window_usage")
    suspend fun windowUsage(): List<WindowUsageEntity>

    @Upsert
    suspend fun upsertWindowUsage(usage: WindowUsageEntity)

    @Query("DELETE FROM window_usage WHERE dateEpochDay < :beforeEpochDay")
    suspend fun deleteWindowUsageBefore(beforeEpochDay: Long)
}

@Dao
interface TamperEventDao {
    @Query("SELECT * FROM tamper_events ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<TamperEventEntity>>

    @Query("SELECT * FROM tamper_events WHERE delivered = 0 ORDER BY timestampMillis")
    suspend fun undelivered(): List<TamperEventEntity>

    @Insert
    suspend fun insert(event: TamperEventEntity): Long

    @Query("UPDATE tamper_events SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: Long)
}

@Database(
    entities = [
        RuleEntity::class, UsageEntity::class, TamperEventEntity::class, RuleGroupEntity::class, GroupUsageEntity::class,
        ScheduleEntity::class, WindowUsageEntity::class,
    ],
    // Released: every schema change gets a new version and a migration (schemas in app/schemas/).
    // 1 → 2 (1.2): schedules, window usage, scheduleId on rules / groups.
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class LaresDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    abstract fun usageDao(): UsageDao

    abstract fun tamperEventDao(): TamperEventDao

    abstract fun groupDao(): GroupDao

    abstract fun scheduleDao(): ScheduleDao

    companion object {
        const val NAME = "lares.db"
    }
}
