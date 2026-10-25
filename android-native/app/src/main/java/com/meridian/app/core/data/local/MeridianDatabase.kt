package com.meridian.app.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        PositionEntity::class,
        TradeEntity::class,
        LimitOrderEntity::class,
        AShareAccountEntity::class,
        AShareHoldingEntity::class,
        AShareTradeEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class MeridianDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun aShareDao(): AShareDao
}
