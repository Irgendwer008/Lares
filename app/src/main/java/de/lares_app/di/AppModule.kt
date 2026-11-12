/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.lares_app.blocking.BlockingController
import de.lares_app.core.alerts.AlertChannelRepository
import de.lares_app.core.alerts.AlertSender
import de.lares_app.core.data.UnlockGate
import de.lares_app.core.data.GroupStore
import de.lares_app.core.data.RuleRepository
import de.lares_app.core.data.RuleStore
import de.lares_app.core.data.SecretCipher
import de.lares_app.core.data.SettingsRepository
import de.lares_app.core.data.StateStore
import de.lares_app.core.data.TamperLog
import de.lares_app.core.time.Clock
import de.lares_app.core.unlock.UnlockManager
import de.lares_app.platform.AlertMessages
import de.lares_app.platform.AndroidClock
import de.lares_app.platform.NetworkAlertSender
import de.lares_app.platform.PermissionStatus
import de.lares_app.platform.AppCatalog
import de.lares_app.platform.DataStoreStateStore
import de.lares_app.platform.ExemptApps
import de.lares_app.platform.InstalledBrowsers
import de.lares_app.platform.LimitNotifier
import de.lares_app.platform.UsageRepository
import de.lares_app.platform.KeystoreSecretCipher
import de.lares_app.platform.db.LaresDatabase
import de.lares_app.platform.db.RoomGroupStore
import de.lares_app.platform.db.RoomRuleStore
import de.lares_app.platform.db.RoomTamperLog
import javax.inject.Singleton

/** Wires the pure core classes to their Android implementations. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun clock(@ApplicationContext context: Context): Clock = AndroidClock(context)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): LaresDatabase =
        Room.databaseBuilder(context, LaresDatabase::class.java, LaresDatabase.NAME).build()

    @Provides @Singleton
    fun stateStore(@ApplicationContext context: Context): StateStore = DataStoreStateStore(context)

    @Provides @Singleton
    fun secretCipher(): SecretCipher = KeystoreSecretCipher()

    @Provides @Singleton
    fun ruleStore(db: LaresDatabase, clock: Clock): RuleStore = RoomRuleStore(db.ruleDao(), clock)

    @Provides @Singleton
    fun groupStore(db: LaresDatabase, clock: Clock): GroupStore = RoomGroupStore(db.groupDao(), clock)

    @Provides @Singleton
    fun tamperLog(db: LaresDatabase, clock: Clock): TamperLog = RoomTamperLog(db.tamperEventDao(), clock)

    @Provides @Singleton
    fun unlockGate(store: StateStore, clock: Clock) = UnlockGate(store, clock)

    @Provides @Singleton
    fun ruleRepository(store: RuleStore, groups: GroupStore, gate: UnlockGate) = RuleRepository(store, groups, gate)

    @Provides @Singleton
    fun settingsRepository(store: StateStore, gate: UnlockGate, clock: Clock) = SettingsRepository(store, gate, clock)

    @Provides @Singleton
    fun alertSender(): AlertSender = NetworkAlertSender()

    @Provides @Singleton
    fun alertMessages(@ApplicationContext context: Context) = AlertMessages(context)

    @Provides @Singleton
    fun alertChannels(store: StateStore, gate: UnlockGate, cipher: SecretCipher) = AlertChannelRepository(store, gate, cipher)

    @Provides @Singleton
    fun permissionStatus(@ApplicationContext context: Context) = PermissionStatus(context)

    @Provides @Singleton
    fun exemptApps(@ApplicationContext context: Context) = ExemptApps(context)

    @Provides @Singleton
    fun limitNotifier(@ApplicationContext context: Context) = LimitNotifier(context)

    @Provides @Singleton
    fun usageRepository(db: LaresDatabase, store: StateStore, clock: Clock) =
        UsageRepository(db.usageDao(), db.groupDao(), db.ruleDao(), store, clock)

    @Provides @Singleton
    fun blockingController(
        rules: RuleStore,
        groups: GroupStore,
        store: StateStore,
        usage: UsageRepository,
        exempt: ExemptApps,
        catalog: AppCatalog,
        notifier: LimitNotifier,
        clock: Clock,
        @ApplicationContext context: Context,
    ) = BlockingController(rules, groups, store, usage, exempt, InstalledBrowsers(context), catalog, notifier, clock)

    @Provides @Singleton
    fun appCatalog(@ApplicationContext context: Context) = AppCatalog(context)

    @Provides @Singleton
    fun unlockManager(store: StateStore, clock: Clock, cipher: SecretCipher, tamperLog: TamperLog) =
        UnlockManager(store, clock, cipher, tamperLog)
}
