package com.tradesim.app.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        PositionEntity::class,
        TradeEntity::class,
        LimitOrderEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class TradeSimDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
}
