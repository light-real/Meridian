package com.meridian.app.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AShareDao {

    @Query("SELECT * FROM a_share_account WHERE id = 1")
    fun observeAccount(): Flow<AShareAccountEntity?>

    @Query("SELECT * FROM a_share_holdings ORDER BY updatedAt DESC")
    fun observeHoldings(): Flow<List<AShareHoldingEntity>>

    @Query("SELECT * FROM a_share_trades ORDER BY createdAt DESC")
    fun observeTrades(): Flow<List<AShareTradeEntity>>

    @Query("SELECT * FROM a_share_account WHERE id = 1")
    suspend fun getAccount(): AShareAccountEntity?

    @Query("SELECT * FROM a_share_holdings ORDER BY updatedAt DESC")
    suspend fun getHoldings(): List<AShareHoldingEntity>

    @Query("SELECT * FROM a_share_trades ORDER BY createdAt DESC")
    suspend fun getTrades(): List<AShareTradeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(entity: AShareAccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHoldings(entities: List<AShareHoldingEntity>)

    @Insert
    suspend fun insertTrade(entity: AShareTradeEntity)

    @Query("DELETE FROM a_share_holdings")
    suspend fun clearHoldings()

    @Query("DELETE FROM a_share_trades")
    suspend fun clearTrades()

    @Query(
        "DELETE FROM a_share_trades WHERE id NOT IN " +
            "(SELECT id FROM a_share_trades ORDER BY createdAt DESC LIMIT :limit)",
    )
    suspend fun trimTrades(limit: Int)

    @Transaction
    suspend fun replaceHoldings(entities: List<AShareHoldingEntity>) {
        clearHoldings()
        upsertHoldings(entities)
    }

    @Transaction
    suspend fun replaceAll(
        account: AShareAccountEntity,
        holdings: List<AShareHoldingEntity>,
        trades: List<AShareTradeEntity>,
    ) {
        upsertAccount(account)
        clearHoldings()
        upsertHoldings(holdings)
        clearTrades()
        trades.forEach { insertTrade(it) }
    }
}
