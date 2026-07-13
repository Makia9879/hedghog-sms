package com.makia.hedgehogsms

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.makia.hedgehogsms.data.AndroidSmsSource
import com.makia.hedgehogsms.data.AppDatabase
import com.makia.hedgehogsms.data.AndroidSubscriptionSource
import com.makia.hedgehogsms.sync.SmsSyncScheduler
import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.makia.hedgehogsms.classification.TrainingRepository
import com.makia.hedgehogsms.classification.TextFeatureExtractor
import com.makia.hedgehogsms.security.AndroidFeatureHmacKey
import com.makia.hedgehogsms.scan.AndroidHistoryDeviceStateSource
import com.makia.hedgehogsms.scan.HistoryDeviceStateSource

class HedgehogApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { container.database.trainingDao().clearIncompatibleKeyData(container.featureHmacKey.keyId) }
        SmsSyncScheduler.scheduleReconcile(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) applicationScope.launch { SmsSyncScheduler.request(this@HedgehogApplication) }
            }
            override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    val container by lazy {
        AppContainer(
            context = this,
            smsSource = AndroidSmsSource(contentResolver),
            subscriptionSource = AndroidSubscriptionSource(this),
            historyDeviceState = AndroidHistoryDeviceStateSource(this),
            database = Room.databaseBuilder(this, AppDatabase::class.java, "hedgehog.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build(),
        )
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `scan_run` ADD COLUMN `estimated` INTEGER")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `training_sample` (`sampleId` INTEGER NOT NULL, `sourceMessageId` INTEGER, `labelId` INTEGER NOT NULL, `confirmationSource` TEXT NOT NULL, `featureVersion` INTEGER NOT NULL, `keyId` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sampleId`))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_training_sample_sourceMessageId` ON `training_sample` (`sourceMessageId`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `training_feature` (`sampleId` INTEGER NOT NULL, `bucket` INTEGER NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`sampleId`, `bucket`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `model_class_stat` (`labelId` INTEGER NOT NULL, `keyId` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `documentCount` INTEGER NOT NULL, `featureCount` INTEGER NOT NULL, PRIMARY KEY(`labelId`, `keyId`, `schemaVersion`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `model_feature_stat` (`labelId` INTEGER NOT NULL, `bucket` INTEGER NOT NULL, `keyId` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`labelId`, `bucket`, `keyId`, `schemaVersion`))""")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `message_classification` (`sourceMessageId` INTEGER NOT NULL, `isOtp` INTEGER NOT NULL, `platformKey` TEXT, `platformDisplayName` TEXT, `status` TEXT NOT NULL, `source` TEXT, `isHumanConfirmed` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sourceMessageId`))""")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `sync_state` (`id` TEXT NOT NULL, `requestedGeneration` INTEGER NOT NULL, `completedGeneration` INTEGER NOT NULL, `highWaterId` INTEGER NOT NULL, `cursorDate` INTEGER, `cursorId` INTEGER, `baselineHighWaterId` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `scan_run` (`id` TEXT NOT NULL, `generation` INTEGER NOT NULL, `status` TEXT NOT NULL, `upperDate` INTEGER, `upperId` INTEGER, `cursorDate` INTEGER, `cursorId` INTEGER, `processed` INTEGER NOT NULL, `lastError` TEXT, `startedAt` INTEGER, `updatedAt` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`id`))""")
    }
}

data class AppContainer(
    val context: android.content.Context,
    val smsSource: com.makia.hedgehogsms.data.SmsSource,
    val subscriptionSource: com.makia.hedgehogsms.data.SubscriptionSource,
    val historyDeviceState: HistoryDeviceStateSource,
    val database: AppDatabase,
) {
    val featureHmacKey by lazy { AndroidFeatureHmacKey(context) }
    val featureExtractor by lazy { TextFeatureExtractor(featureHmacKey) }
    val trainingRepository by lazy { TrainingRepository(database) }
}
