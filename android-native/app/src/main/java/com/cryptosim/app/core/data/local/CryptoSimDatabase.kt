package com.cryptosim.app.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AccountEntity::class, PositionEntity::class, TradeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CryptoSimDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
}
